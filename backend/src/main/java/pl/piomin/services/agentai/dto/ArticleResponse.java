package pl.piomin.services.agentai.dto;

import pl.piomin.services.agentai.model.KnowledgeArticle;

public record ArticleResponse(long id, String title, String tags, String content) {

    public static ArticleResponse of(KnowledgeArticle article) {
        return new ArticleResponse(article.getId(), article.getTitle(), article.getTags(), article.getContent());
    }
}
