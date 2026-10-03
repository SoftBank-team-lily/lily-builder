package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdgePrewarmTest {

    private static final String BASE = "https://blog.lilycloud.kr/";

    private final Map<String, EdgePrewarm.Page> pages = new HashMap<>();
    private final List<String> opened = new ArrayList<>();
    private final EdgePrewarm prewarm = new EdgePrewarm(uri -> {
        opened.add(uri.toString());
        EdgePrewarm.Page page = pages.get(uri.toString());
        if (page == null) {
            return new EdgePrewarm.Page(404, "text/html", "");
        }
        return page;
    }, Duration.ZERO);

    private static EdgePrewarm.Page html(String body) {
        return new EdgePrewarm.Page(200, "text/html; charset=utf-8", body);
    }

    @Test
    void 첫_화면과_그_안의_같은_호스트_링크와_sitemap_loc를_한_번씩_연다() {
        pages.put(BASE, html("""
                <link href="/static/app.css" rel="stylesheet">
                <a href="/posts/1">1</a> <a href='posts/2'>2</a> <a href="/posts/1#top">again</a>
                <img src="https://blog.lilycloud.kr/img/logo.png">
                """));
        pages.put(BASE + "sitemap.xml", new EdgePrewarm.Page(200, "application/xml",
                "<urlset><url><loc>https://blog.lilycloud.kr/posts/3</loc></url><url><loc>https://blog.lilycloud.kr/posts/1</loc></url></urlset>"));
        pages.put(BASE + "static/app.css", new EdgePrewarm.Page(200, "text/css", ""));
        pages.put(BASE + "posts/1", html(""));
        pages.put(BASE + "posts/2", html(""));
        pages.put(BASE + "img/logo.png", new EdgePrewarm.Page(200, "image/png", ""));
        pages.put(BASE + "posts/3", html(""));

        String line = prewarm.warm(BASE);

        assertThat(opened).containsExactly(BASE, BASE + "sitemap.xml", BASE + "static/app.css", BASE + "posts/1",
                BASE + "posts/2", BASE + "img/logo.png", BASE + "posts/3");
        assertThat(line).isEqualTo("prewarm: 6/6 pages 200 from " + BASE);
    }

    @Test
    void 다른_호스트_링크와_mailto_javascript_Worker_내부_경로는_열지_않는다() {
        pages.put(BASE, html("""
                <a href="https://other.example/x">x</a> <a href="mailto:a@b.c">m</a>
                <a href="javascript:void(0)">j</a> <a href="/__lily_edge/snap/">s</a> <a href="/about">a</a>
                """));
        pages.put(BASE + "about", html(""));

        prewarm.warm(BASE);

        assertThat(opened).containsExactly(BASE, BASE + "sitemap.xml", BASE + "about");
    }

    @Test
    void 링크가_많아도_첫_화면을_포함해_50개까지만_연다() {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            links.append("<a href=\"/posts/").append(i).append("\">").append(i).append("</a>");
        }
        pages.put(BASE, html(links.toString()));

        String line = prewarm.warm(BASE);

        assertThat(opened.stream().filter(uri -> !uri.endsWith("sitemap.xml"))).hasSize(EdgePrewarm.LIMIT);
        assertThat(line).isEqualTo("prewarm: 1/50 pages 200 from " + BASE);
    }

    @Test
    void 링크의_amp_엔티티는_풀어서_연다() {
        pages.put(BASE, html("<a href=\"/posts?page=2&amp;sort=new\">2</a>"));

        prewarm.warm(BASE);

        assertThat(opened).contains(BASE + "posts?page=2&sort=new");
    }

    @Test
    void 페이지_요청이_실패해도_나머지를_열고_200인_수만_센다() {
        pages.put(BASE, html("<a href=\"/boom\">b</a><a href=\"/ok\">o</a>"));
        pages.put(BASE + "ok", html(""));
        EdgePrewarm failing = new EdgePrewarm(uri -> {
            opened.add(uri.toString());
            if (uri.getPath().equals("/boom")) {
                throw new IllegalStateException("timeout");
            }
            return pages.getOrDefault(uri.toString(), new EdgePrewarm.Page(404, "text/html", ""));
        }, Duration.ZERO);

        String line = failing.warm(BASE);

        assertThat(opened).contains(BASE + "ok");
        assertThat(line).isEqualTo("prewarm: 2/3 pages 200 from " + BASE);
    }

    @Test
    void 첫_화면에_닿지_못하면_건너뛰었다고_남긴다() {
        EdgePrewarm unreachable = new EdgePrewarm(uri -> {
            throw new IllegalStateException("connect refused");
        }, Duration.ZERO);

        assertThat(unreachable.warm(BASE)).isEqualTo("prewarm: skipped (" + BASE + " connect refused)");
    }

    @Test
    void 열_주소를_URI로_해석하지_못하는_링크는_건너뛴다() {
        pages.put(BASE, html("<a href=\"/%zz\">bad</a><a href=\"/good\">ok</a>"));
        pages.put(BASE + "good", html(""));

        prewarm.warm(BASE);

        assertThat(opened).containsExactly(BASE, BASE + "sitemap.xml", BASE + "good");
    }
}
