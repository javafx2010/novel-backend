package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.NovelRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class NovelService {

    private final NovelRepository novelRepository;
    private final CategoryRepository categoryRepository;

    public NovelService(NovelRepository novelRepository, CategoryRepository categoryRepository) {
        this.novelRepository = novelRepository;
        this.categoryRepository = categoryRepository;
    }

    public List<Category> listCategories() {
        return categoryRepository.findAllByOrderBySortNoAsc();
    }

    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("分类不存在：id=" + id));
    }

    @Cacheable(cacheNames = CacheNames.NOVEL_DETAIL, key = "#id")
    public Novel detail(Long id) {
        return novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("小说不存在：id=" + id));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, key = "#id")
    public Novel viewDetail(Long id) {
        Novel novel = detail(id);
        novel.setViewCount(novel.getViewCount() + 1);
        return novelRepository.save(novel);
    }

    /** 热门榜单：按点击量排序 */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'hot'")
    public List<Novel> hotList() {
        return novelRepository.findTop10ByOrderByViewCountDesc();
    }

    /** 精品推荐：按评分排序 */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'recommend'")
    public List<Novel> recommendList() {
        return novelRepository.findTop8ByOrderByRatingDesc();
    }

    /** 最近更新 */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'latest'")
    public List<Novel> latestUpdatedList() {
        return novelRepository.findTop8ByOrderByUpdatedAtDesc();
    }

    /** 完本推荐 */
    public Page<Novel> finishedPage(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return novelRepository.findByStatusOrderByViewCountDesc(Novel.STATUS_FINISHED, pageable);
    }

    public Page<Novel> pageByCategory(Category category, String sort, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if ("update".equalsIgnoreCase(sort)) {
            return novelRepository.findByCategoryOrderByUpdatedAtDesc(category, pageable);
        }
        return novelRepository.findByCategoryOrderByViewCountDesc(category, pageable);
    }

    /** 排行榜：默认按点击量排序，type=rating 时按评分排序 */
    public Page<Novel> rankPage(String type, int page, int size) {
        Sort sort = "rating".equalsIgnoreCase(type)
                ? Sort.by(Sort.Direction.DESC, "rating", "viewCount")
                : Sort.by(Sort.Direction.DESC, "viewCount");
        return novelRepository.findAll(PageRequest.of(page, size, sort));
    }

    public Page<Novel> search(String keyword, int page, int size) {
        if (keyword == null || keyword.isBlank()) {
            return Page.empty();
        }
        return novelRepository.search(keyword.trim(), PageRequest.of(page, size));
    }

    /** 同分类推荐：排除自身 */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'related:' + #novel.id + ':' + #limit")
    public List<Novel> relatedList(Novel novel, int limit) {
        if (novel.getId() == null || novel.getCategory() == null) {
            return List.of();
        }
        return novelRepository.findTop6ByCategoryAndIdNotOrderByViewCountDesc(
                        novel.getCategory(), novel.getId()).stream()
                .limit(limit)
                .toList();
    }

    public long countAll() {
        return novelRepository.count();
    }
}
