package com.novel.browse.repository;

import com.novel.browse.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderBySortNoAsc();

    Optional<Category> findByName(String name);
}
