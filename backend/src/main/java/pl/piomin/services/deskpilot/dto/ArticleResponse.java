package pl.piomin.services.deskpilot.dto;

import pl.piomin.services.deskpilot.model.KnowledgeArticle;

public record ArticleResponse(long id, String title, String tags, String content) {

    public static ArticleResponse of(KnowledgeArticle article) {
        return new ArticleResponse(article.getId(), article.getTitle(), article.getTags(), article.getContent());
    }
}
