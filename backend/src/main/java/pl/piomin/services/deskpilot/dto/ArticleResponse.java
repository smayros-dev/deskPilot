package pl.piomin.services.deskpilot.dto;

import pl.piomin.services.deskpilot.model.KnowledgeArticle;

/**
 * @param documentId    the uploaded document this article is a chunk of, if any
 * @param documentTitle its title
 */
public record ArticleResponse(long id, String title, String tags, String content, Long documentId, String documentTitle) {

    public static ArticleResponse of(KnowledgeArticle article) {
        return of(article, null);
    }

    public static ArticleResponse of(KnowledgeArticle article, String documentTitle) {
        return new ArticleResponse(article.getId(), article.getTitle(), article.getTags(), article.getContent(),
                article.getDocumentId(), documentTitle);
    }
}
