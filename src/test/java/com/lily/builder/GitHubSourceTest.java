package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubSourceTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String TREE = """
            {"sha":"%s","truncated":false,"tree":[
              {"path":"src/main/resources/db/migration","type":"tree"},
              {"path":"src/main/resources/db/migration/V1__init.sql","type":"blob"},
              {"path":"src/main/resources/db/migration/U1__init.sql","type":"blob"},
              {"path":"src/main/resources/db/migration/README.md","type":"blob"},
              {"path":"src/main/resources/db/migration/old/V2__col.sql","type":"blob"},
              {"path":"backend/src/main/resources/db/migration/V9__other.sql","type":"blob"},
              {"path":"src/main/resources/application.yml","type":"blob"}
            ]}""".formatted(SHA);

    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final GitHubSource source = new GitHubSource(http, "http://api", "http://raw");

    private static BuildRequest request(String rootDir, String token, String migrationsPath, Boolean migrate) {
        return new BuildRequest("https://github.com/org/repo.git", "feature/x", token, rootDir, "blog", 8080,
                "postgres", null, null, Map.of(), null, null, migrationsPath, migrate);
    }

    @Test
    void 분석파일은_크기를_제한하고_같은_커밋에서_읽는다() {
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/backend/package.json"))
            .andRespond(withSuccess("x".repeat(65537), MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> source.analysisFile(request("backend",null,null,null),SHA,"package.json"))
            .hasMessage("manifest_too_large");
        server.verify();
    }

    @Test
    void 브랜치_끝_커밋을_읽는다() {
        server.expect(requestTo("http://api/repos/org/repo/commits/feature/x"))
                .andExpect(header("Accept", "application/vnd.github.sha"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(SHA + "\n", MediaType.TEXT_PLAIN));

        assertThat(source.resolveCommit(request(null, null, null, null))).isEqualTo(SHA);
        server.verify();
    }

    @Test
    void 브랜치가_없으면_이유를_알린다() {
        server.expect(requestTo("http://api/repos/org/repo/commits/feature/x"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> source.resolveCommit(request(null, null, null, null)))
                .hasMessageContaining("feature/x").hasMessageContaining("422");
    }

    @Test
    void 같은_커밋의_마이그레이션_폴더에서_V_U_파일만_모은다() {
        server.expect(requestTo("http://api/repos/org/repo/git/trees/" + SHA + "?recursive=1"))
                .andExpect(header("Authorization", "Bearer ghp_x"))
                .andRespond(withSuccess(TREE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/src/main/resources/db/migration/V1__init.sql"))
                .andExpect(header("Authorization", "Bearer ghp_x"))
                .andRespond(withSuccess("create table a(id int);", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/src/main/resources/db/migration/U1__init.sql"))
                .andRespond(withSuccess("drop table a;", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/src/main/resources/db/migration/old/V2__col.sql"))
                .andRespond(withSuccess("alter table a add column b int;", MediaType.TEXT_PLAIN));

        Map<String, String> files = source.migrations(request(null, "ghp_x", null, null), SHA);

        assertThat(files).containsExactly(
                Map.entry("U1__init.sql", "drop table a;"),
                Map.entry("V1__init.sql", "create table a(id int);"),
                Map.entry("V2__col.sql", "alter table a add column b int;"));
        server.verify();
    }

    @Test
    void db_pgroll에_pgroll_파일이_있으면_SQL_대신_그_폴더_바로_아래_파일만_모은다() {
        String tree = """
                {"sha":"%s","truncated":false,"tree":[
                  {"path":"src/main/resources/db/migration/V1__init.sql","type":"blob"},
                  {"path":"db/pgroll/01_create_posts.yaml","type":"blob"},
                  {"path":"db/pgroll/02_add_slug.json","type":"blob"},
                  {"path":"db/pgroll/README.md","type":"blob"},
                  {"path":"db/pgroll/old/03_skip.yaml","type":"blob"}
                ]}""".formatted(SHA);
        server.expect(requestTo("http://api/repos/org/repo/git/trees/" + SHA + "?recursive=1"))
                .andRespond(withSuccess(tree, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/db/pgroll/01_create_posts.yaml"))
                .andRespond(withSuccess("operations: []", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/db/pgroll/02_add_slug.json"))
                .andRespond(withSuccess("{\"operations\":[]}", MediaType.TEXT_PLAIN));

        Map<String, String> files = source.migrations(request(null, null, null, null), SHA);

        assertThat(files).containsExactly(
                Map.entry("01_create_posts.yaml", "operations: []"),
                Map.entry("02_add_slug.json", "{\"operations\":[]}"));
        server.verify();
    }

    @Test
    void 온프레미스용_SQL_마이그레이션은_db_pgroll이_있어도_SQL_파일만_모은다() {
        String tree = """
                {"sha":"%s","truncated":false,"tree":[
                  {"path":"src/main/resources/db/migration/V1__init.sql","type":"blob"},
                  {"path":"db/pgroll/01_create_posts.yaml","type":"blob"}
                ]}""".formatted(SHA);
        server.expect(requestTo("http://api/repos/org/repo/git/trees/" + SHA + "?recursive=1"))
                .andRespond(withSuccess(tree, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/src/main/resources/db/migration/V1__init.sql"))
                .andRespond(withSuccess("create table a(id int);", MediaType.TEXT_PLAIN));

        assertThat(source.sqlMigrations(request(null, null, null, null), SHA))
                .containsExactly(Map.entry("V1__init.sql", "create table a(id int);"));
        server.verify();
    }

    @Test
    void rootDir_기준으로_폴더를_찾는다() {
        server.expect(requestTo("http://api/repos/org/repo/git/trees/" + SHA + "?recursive=1"))
                .andRespond(withSuccess(TREE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/backend/src/main/resources/db/migration/V9__other.sql"))
                .andRespond(withSuccess("select 1;", MediaType.TEXT_PLAIN));

        assertThat(source.migrations(request("/backend/", null, null, null), SHA)).containsOnlyKeys("V9__other.sql");
    }

    @Test
    void 폴더가_없으면_비어_있고_migrate_false_면_GitHub_을_부르지_않는다() {
        server.expect(requestTo("http://api/repos/org/repo/git/trees/" + SHA + "?recursive=1"))
                .andRespond(withSuccess(TREE, MediaType.APPLICATION_JSON));

        assertThat(source.migrations(request(null, null, "db/none", null), SHA)).isEmpty();
        assertThat(source.migrations(request(null, null, null, false), SHA)).isEmpty();
        server.verify();
    }

    @Test
    void 하위_폴더끼리_파일명이_겹치면_실패한다() {
        server.expect(requestTo("http://api/repos/org/repo/git/trees/" + SHA + "?recursive=1"))
                .andRespond(withSuccess("""
                        {"tree":[{"path":"db/a/V1__x.sql","type":"blob"},{"path":"db/b/V1__x.sql","type":"blob"}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/db/a/V1__x.sql")).andRespond(withSuccess("a", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/db/b/V1__x.sql")).andRespond(withSuccess("b", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> source.migrations(request(null, null, "db", null), SHA))
                .hasMessageContaining("겹친다");
    }

    @Test
    void 폴더_경로를_정리한다() {
        assertThat(GitHubSource.folder(request(null, null, null, null))).isEqualTo("src/main/resources/db/migration");
        assertThat(GitHubSource.folder(request("/api/", null, "/db//migration/", null))).isEqualTo("api/db/migration");
        assertThat(GitHubSource.repo(request(null, null, null, null))).isEqualTo("org/repo");
    }

    @Test
    void 커밋의_파일_하나를_rootDir_기준으로_읽고_없으면_null() {
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/backend/Dockerfile"))
                .andExpect(header("Authorization", "Bearer ghp_x"))
                .andRespond(withSuccess("EXPOSE 3000", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://raw/org/repo/" + SHA + "/backend/package.json"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        BuildRequest request = request("/backend/", "ghp_x", null, null);
        assertThat(source.file(request, SHA, "Dockerfile")).isEqualTo("EXPOSE 3000");
        assertThat(source.file(request, SHA, "package.json")).isNull();
        server.verify();
    }
}
