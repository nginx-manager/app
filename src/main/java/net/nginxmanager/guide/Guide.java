package net.nginxmanager.guide;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 관리도구 안에서 보는 사용 설명서. 원문은 {@code classpath:guide/<언어>/}의 Markdown이고 그림은 {@code static/guide/images/}에 있다.
 * 원문은 GitHub에서도 읽히도록 상대 링크(04-sites.md#…, images/x.png)로 쓰고, 렌더링할 때 관리도구 주소로 바꾼다.
 * 원문은 빌드 때 고정이므로 시작할 때 한 번 렌더링해 둔다.
 */
@Component
public class Guide {

    /** 설명서 첫 화면(차례). 주소는 /guide. */
    public static final String INDEX = "README";

    static final String DEFAULT_LANGUAGE = "ko";

    private static final Pattern FILE = Pattern.compile("(README|\\d\\d-[a-z0-9-]+)\\.md");
    private static final Pattern LOCAL_LINK = Pattern.compile("(?:(README|\\d\\d-[a-z0-9-]+)\\.md)?(#.*)?");
    private static final Pattern HTML_IMAGE = Pattern.compile("(<img\\s[^>]*src=\")images/");

    private final String base;
    private final Map<String, Book> books;

    public Guide(@Value("${server.servlet.context-path:}") String contextPath) {
        this.base = contextPath + "/guide";
        this.books = load();
        if (!books.containsKey(DEFAULT_LANGUAGE)) {
            throw new IllegalStateException("guide/" + DEFAULT_LANGUAGE + " not found");
        }
    }

    /** 설명서 장 하나. slug는 파일 이름(확장자 뺌), sections는 본문의 ## 소제목, ids는 모든 소제목 id. */
    public record Chapter(String slug, String title, String html, List<Section> sections, Set<String> ids) {
    }

    public record Section(String id, String title) {
    }

    /** 한 언어의 설명서: 첫 화면과 장 순서대로. */
    public record Book(String language, Chapter index, List<Chapter> chapters) {

        public Optional<Chapter> chapter(String slug) {
            if (INDEX.equals(slug)) {
                return Optional.of(index);
            }
            return chapters.stream().filter(c -> c.slug().equals(slug)).findFirst();
        }
    }

    /** 그 언어의 설명서. 번역이 없으면 한국어. */
    public Book book(Locale locale) {
        Book book = books.get(locale.getLanguage());
        return book != null ? book : books.get(DEFAULT_LANGUAGE);
    }

    /** 장 주소. 첫 화면은 /guide. */
    public String url(String slug) {
        return INDEX.equals(slug) ? base : base + "/" + slug;
    }

    private Map<String, Book> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:guide/*/*.md");
            Map<String, Map<String, String>> sources = new HashMap<>();
            for (Resource resource : resources) {
                Matcher file = FILE.matcher(String.valueOf(resource.getFilename()));
                if (!file.matches()) {
                    continue;
                }
                String path = resource.getURL().toString();
                String parent = path.substring(0, path.lastIndexOf('/'));
                String language = parent.substring(parent.lastIndexOf('/') + 1);
                sources.computeIfAbsent(language, l -> new HashMap<>())
                        .put(file.group(1), resource.getContentAsString(StandardCharsets.UTF_8));
            }
            Map<String, Book> loaded = new LinkedHashMap<>();
            sources.forEach((language, files) -> {
                if (files.containsKey(INDEX)) {
                    loaded.put(language, book(language, files));
                }
            });
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Book book(String language, Map<String, String> files) {
        List<Chapter> chapters = new ArrayList<>();
        files.keySet().stream()
                .filter(slug -> !slug.equals(INDEX))
                .sorted(Comparator.naturalOrder())
                .forEach(slug -> chapters.add(render(slug, files.get(slug))));
        return new Book(language, render(INDEX, files.get(INDEX)), List.copyOf(chapters));
    }

    Chapter render(String slug, String markdown) {
        List<org.commonmark.Extension> extensions = List.of(TablesExtension.create());
        Node document = Parser.builder().extensions(extensions).build().parse(markdown);

        Map<Heading, String> headingIds = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        List<Section> sections = new ArrayList<>();
        String[] title = {slug};
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                String text = text(heading);
                String id = unique(githubId(text), ids);
                headingIds.put(heading, id);
                if (heading.getLevel() == 1 && title[0].equals(slug)) {
                    title[0] = text;
                } else if (heading.getLevel() == 2) {
                    sections.add(new Section(id, text));
                }
            }
        });

        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                    if (node instanceof Heading heading) {
                        attributes.put("id", headingIds.get(heading));
                    } else if (node instanceof Link link) {
                        String href = link.getDestination();
                        if (href.startsWith("http://") || href.startsWith("https://")) {
                            attributes.put("target", "_blank");
                            attributes.put("rel", "noopener");
                        } else {
                            attributes.put("href", localHref(href));
                        }
                    } else if (node instanceof Image image) {
                        attributes.put("src", localHref(image.getDestination()));
                    }
                })
                .build();
        String html = HTML_IMAGE.matcher(renderer.render(document)).replaceAll("$1" + Matcher.quoteReplacement(base) + "/images/");
        return new Chapter(slug, title[0], html, List.copyOf(sections), Set.copyOf(ids));
    }

    /** 원문의 상대 링크를 관리도구 주소로. 04-sites.md#a → /guide/04-sites#a, README.md → /guide, images/x.png → /guide/images/x.png */
    String localHref(String href) {
        if (href.startsWith("images/")) {
            return base + "/" + href;
        }
        Matcher link = LOCAL_LINK.matcher(href);
        if (!link.matches() || href.isEmpty()) {
            return href;
        }
        String fragment = link.group(2) == null ? "" : link.group(2);
        return link.group(1) == null ? fragment : url(link.group(1)) + fragment;
    }

    /** GitHub과 같은 소제목 id: 소문자로, 글자·숫자·공백·-·_ 말고는 지우고, 공백은 -로. 한글은 그대로 둔다. */
    static String githubId(String text) {
        StringBuilder id = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(c -> {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                id.appendCodePoint(c);
            } else if (c == ' ') {
                id.append('-');
            }
        });
        return id.toString();
    }

    private static String unique(String id, Set<String> ids) {
        String candidate = id;
        for (int n = 1; !ids.add(candidate); n++) {
            candidate = id + "-" + n;
        }
        return candidate;
    }

    private static String text(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                text.append(t.getLiteral());
            }

            @Override
            public void visit(Code code) {
                text.append(code.getLiteral());
            }
        });
        return text.toString();
    }
}
