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
}
