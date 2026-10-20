package pl.piomin.services.deskpilot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;

public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, Long> {

    List<KnowledgeArticle> findByDocumentIdOrderByIdAsc(Long documentId);

    @Modifying
    @Query("delete from KnowledgeArticle a where a.documentId = :documentId")
    int deleteByDocumentId(Long documentId);

    interface KeywordHit {
        Long getId();

        Double getRank();
    }

    /**
     * Full-text search where any query word may match (OR): {@code plainto_tsquery} stems the words and drops stop
     * words, then its {@code &} operators are turned into {@code |}. Ranked by {@code ts_rank_cd} (title and tags weigh more).
     */
    @Query(value = """
            select a.id as id, ts_rank_cd(a.search_vector, q.query) as rank
            from knowledge_article a,
                 (select cast(replace(cast(plainto_tsquery('english', :text) as text), '&', '|') as tsquery) as query) q
            where a.search_vector @@ q.query
            order by rank desc
            limit :limit""", nativeQuery = true)
    List<KeywordHit> keywordSearch(String text, int limit);
}
