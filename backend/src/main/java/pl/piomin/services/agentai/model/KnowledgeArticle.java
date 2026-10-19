package pl.piomin.services.agentai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Helpdesk knowledge-base article; the source of truth that gets embedded into the vector store. */
@Entity
@Table(name = "knowledge_article")
public class KnowledgeArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Comma-separated keywords, e.g. "vpn,network,remote". */
    @Column(length = 300)
    private String tags;

    protected KnowledgeArticle() {
    }

    public KnowledgeArticle(String title, String content, String tags) {
        this.title = title;
        this.content = content;
        this.tags = tags;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getTags() {
        return tags;
    }
}
