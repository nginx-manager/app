package net.nginxmanager.guide;

import net.nginxmanager.common.NotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Locale;

/** 사용 설명서 화면. 로그인한 모든 사용자가 본다. */
@Controller
public class GuideController {

    private final Guide guide;

    public GuideController(Guide guide) {
        this.guide = guide;
    }

    @GetMapping("/guide")
    public String index(Locale locale, Model model) {
        return show(Guide.INDEX, locale, model);
    }

    @GetMapping("/guide/{slug:\\d\\d-[a-z0-9-]+}")
    public String chapter(@PathVariable String slug, Locale locale, Model model) {
        return show(slug, locale, model);
    }

    private String show(String slug, Locale locale, Model model) {
        Guide.Book book = guide.book(locale);
        Guide.Chapter chapter = book.chapter(slug).orElseThrow(() -> new NotFoundException("guide chapter " + slug));
        List<Guide.Chapter> chapters = book.chapters();
        int at = chapters.indexOf(chapter);
        model.addAttribute("home", guide.url(Guide.INDEX));
        model.addAttribute("toc", chapters.stream().map(this::entry).toList());
        model.addAttribute("chapter", chapter);
        model.addAttribute("isIndex", Guide.INDEX.equals(slug));
        model.addAttribute("prev", at > 0 ? entry(chapters.get(at - 1)) : at < 0 ? null : entry(book.index()));
        model.addAttribute("next", at + 1 < chapters.size() ? entry(chapters.get(at + 1)) : null);
        model.addAttribute("koreanOnly", !book.language().equals(locale.getLanguage()));
        return "guide/view";
    }

    /** 목차와 이전·다음 링크 하나. */
    public record Entry(String slug, String title, String url, boolean index) {
    }

    private Entry entry(Guide.Chapter chapter) {
        return new Entry(chapter.slug(), chapter.title(), guide.url(chapter.slug()), Guide.INDEX.equals(chapter.slug()));
    }
}
