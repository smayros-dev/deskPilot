import { DatePipe, DecimalPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { KnowledgeDocument } from '../core/models';
import { Notifications } from '../core/notifications';
import { DOCUMENTS_POLL_MS, DocumentsApi, documentProblem } from './documents-api';

/**
 * Admins add documents to the knowledge base: drop or pick a file, it is uploaded, then split into articles and
 * embedded on the server; the list shows the progress and lets admins re-index or delete a document.
 */
@Component({
  selector: 'app-documents-panel',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" aria-labelledby="documents-title">
      <h2 id="documents-title">Documents</h2>
      <div
        class="drop-zone"
        [class.dragging]="dragging()"
        (dragover)="onDragOver($event)"
        (dragleave)="dragging.set(false)"
        (drop)="onDrop($event)"
      >
        <p>Drop a PDF, Markdown, text or HTML file here (up to 10 MB), or</p>
        <label class="button">
          Choose a file
          <input type="file" class="visually-hidden" accept=".pdf,.md,.markdown,.txt,.html,.htm" (change)="onPick($event)" />
        </label>
        <label class="title-field">
          <span>Title (optional)</span>
          <input type="text" maxlength="200" [value]="title()" (input)="title.set($any($event.target).value)" placeholder="Defaults to the file name" />
        </label>
        @if (uploading(); as name) {
          <div class="uploading" role="status">
            <span>Uploading {{ name }}…</span>
            <progress aria-label="Uploading"></progress>
          </div>
        }
        @if (problem(); as message) {
          <p class="error-text" role="alert">{{ message }}</p>
        }
      </div>

      @if (documents.hasValue() && documents.value().length > 0) {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Document</th>
                <th scope="col">Status</th>
                <th scope="col">Articles</th>
                <th scope="col">Size</th>
                <th scope="col">Uploaded</th>
                <th scope="col"><span class="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (document of documents.value(); track document.id) {
                <tr>
                  <td>
                    <strong>{{ document.title }}</strong>
                    <small class="muted">{{ document.fileName }} · {{ document.format.toLowerCase() }}</small>
                  </td>
                  <td>
                    <span class="status" [attr.data-status]="document.status">{{ document.status.toLowerCase() }}</span>
                    @if (document.error) {
                      <small class="error-text">{{ document.error }}</small>
                    }
                  </td>
                  <td>{{ document.chunks }}</td>
                  <td>{{ document.sizeBytes / 1024 | number: '1.0-0' }} KB</td>
                  <td>
                    {{ document.uploadedAt | date: 'short' }}
                    <small class="muted">by {{ document.uploadedBy }}</small>
                  </td>
                  <td class="actions">
                    <button type="button" class="link-button" [disabled]="document.status === 'PROCESSING'" (click)="reindex(document)">Re-index</button>
                    <button type="button" class="link-button danger" (click)="remove(document)">Delete</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else if (documents.hasValue()) {
        <p class="muted">No document uploaded yet. The seeded helpdesk articles are always available.</p>
      }
    </section>
  `,
  styles: `
    h2 { font-size: 1rem; margin: 0 0 0.75rem; }
    .drop-zone {
      display: grid;
      gap: 0.6rem;
      justify-items: start;
      padding: 1rem;
      border: 2px dashed var(--border);
      border-radius: var(--radius);
    }
    .drop-zone.dragging { border-color: var(--accent); background: var(--accent-bg); }
    .drop-zone p { margin: 0; }
    .title-field { display: grid; gap: 0.25rem; width: 100%; max-width: 24rem; }
    .uploading { display: flex; align-items: center; gap: 0.75rem; width: 100%; }
    progress { flex: 1; max-width: 16rem; accent-color: var(--accent); }
    .table-wrap { overflow-x: auto; margin-top: 1rem; }
    table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    th, td { text-align: left; padding: 0.45rem 0.6rem; border-bottom: 1px solid var(--border); vertical-align: top; }
    td small { display: block; }
    .status[data-status='READY'] { color: var(--ok); }
    .status[data-status='PROCESSING'] { color: var(--accent); }
    .status[data-status='FAILED'] { color: var(--danger); }
    .actions { white-space: nowrap; }
    .actions .danger { color: var(--danger); }
    .error-text { color: var(--danger); }
  `,
})
export class DocumentsPanel {
  private readonly api = inject(DocumentsApi);
  private readonly notifications = inject(Notifications);
  private readonly pollMs = inject(DOCUMENTS_POLL_MS);

  /** Emitted when the articles of the knowledge base changed (a document was indexed or deleted). */
  readonly changed = output<void>();

  protected readonly documents = httpResource<KnowledgeDocument[]>(() => this.api.baseUrl);
  protected readonly title = signal('');
  protected readonly dragging = signal(false);
  /** Name of the file being uploaded. */
  protected readonly uploading = signal<string | null>(null);
  protected readonly problem = signal<string | null>(null);

  private readonly processing = computed(() =>
    this.documents.hasValue() ? this.documents.value().filter((d) => d.status === 'PROCESSING').map((d) => d.id) : [],
  );

  constructor() {
    // While documents are being processed on the server, refresh the list; tell the page when one finishes.
    let wasProcessing: readonly number[] = [];
    effect((onCleanup) => {
      const ids = this.processing();
      if (wasProcessing.some((id) => !ids.includes(id))) {
        this.changed.emit();
      }
      wasProcessing = ids;
      if (ids.length === 0) {
        return;
      }
      const timer = setInterval(() => this.documents.reload(), this.pollMs);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      void this.upload(file);
    }
  }

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      void this.upload(file);
    }
  }

  async upload(file: File): Promise<void> {
    const problem = documentProblem(file);
    this.problem.set(problem);
    if (problem) {
      return;
    }
    this.uploading.set(file.name);
    try {
      const document = await firstValueFrom(this.api.upload(file, this.title() || null));
      this.notifications.info(`“${document.title}” uploaded; it is being split into articles.`);
      this.title.set('');
      this.documents.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.uploading.set(null);
    }
  }

  protected async reindex(document: KnowledgeDocument): Promise<void> {
    try {
      await firstValueFrom(this.api.reindex(document.id));
      this.documents.reload();
    } catch {
      // Toast shown by the error interceptor.
    }
  }

  protected async remove(document: KnowledgeDocument): Promise<void> {
    if (!confirm(`Delete “${document.title}” and its ${document.chunks} articles from the knowledge base?`)) {
      return;
    }
    try {
      await firstValueFrom(this.api.delete(document.id));
      this.notifications.info(`“${document.title}” deleted.`);
      this.documents.reload();
      this.changed.emit();
    } catch {
      // Toast shown by the error interceptor.
    }
  }
}
