package com.novel.browse.repository;

import com.novel.browse.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderBySortNoAsc();

    /** Categories of one language, in navigation order */
    List<Category> findAllByLanguageOrderBySortNoAsc(String language);

    /** The category of one language: the same name in another language is another category */
    Optional<Category> findByNameAndLanguage(String name, String language);

    /** Categories no longer holding any book, e.g. the news category after the retention purge */
    @Query("select c from Category c where not exists (select 1 from Novel n where n.category = c)")
    List<Category> findEmptyCategories();
}
