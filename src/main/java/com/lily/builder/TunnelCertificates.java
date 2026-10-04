package com.lily.builder;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 에이전트의 DB 터널 공개키에 SSH CA 로 인증서를 서명한다. 사용자 PC 에 개인키를 나눠 주지 않기 위해서다.
 *
 * <p>배스천의 lily-tunnel 은 이 CA 를 TrustedUserCAKeys 로 믿고, 권한은 AuthorizedPrincipalsCommand 가 인증서 key ID 로 정한다
 * (lily-db-provisioner deploy/k3s/cluster/lily-tunnel-principals.sh).
 * <ul>
 *   <li>{@code agent-{key}}: RDS 포트로의 -L 만</li>
 *   <li>{@code agent-{key}-p{port}}: 위에 더해 배스천 사설 IP 의 그 포트 하나에만 -R (온프레미스 DB 를 클라우드에 연다)</li>
 * </ul>
 * key ID 는 CA 가 서명한 값이라 에이전트가 바꿀 수 없다. 그래서 다른 에이전트의 포트를 가로챌 수 없다.
 * 인증서에는 포워딩 외의 권한을 넣지 않는다.
 */
@Component
public class TunnelCertificates {

    private static final Pattern PUBLIC_KEY = Pattern.compile("ssh-ed25519 [A-Za-z0-9+/]{60,80}={0,2}");

    private final PlatformProperties.Tunnel settings;
    private volatile Path caFile;

    public TunnelCertificates(PlatformProperties props) {
        this.settings = props.tunnel();
    }

    public boolean enabled() {
        return settings.configured();
    }

    public PlatformProperties.Tunnel settings() {
        return settings;
    }

    /**
     * @param publicKey 에이전트가 hello 로 보낸 {@code ssh-ed25519 AAAA... [comment]}
     * @return {@code ssh-ed25519-cert-v01@openssh.com ...} 한 줄
     */
    public String sign(String agentKey, String publicKey) {
        return sign(agentKey, publicKey, null);
    }

    /** @param reversePort 이 에이전트가 배스천에서 열 수 있는 역방향 포트. null 이면 -L 만 */
    public String sign(String agentKey, String publicKey, Integer reversePort) {
        if (!enabled()) {
            throw new IllegalStateException("DB 터널 CA 가 설정되지 않았다");
        }
        String[] parts = publicKey == null ? new String[0] : publicKey.trim().split("\\s+");
        if (parts.length < 2 || !PUBLIC_KEY.matcher(parts[0] + " " + parts[1]).matches()) {
            throw new IllegalArgumentException("ssh-ed25519 공개키가 아니다");
        }
        if (!AgentTokens.KEY.matcher(agentKey).matches()) {
            throw new IllegalArgumentException("에이전트 key 가 아니다");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lily-cert");
            Path pub = dir.resolve("agent.pub");
            Files.writeString(pub, parts[0] + " " + parts[1] + " agent-" + agentKey + "\n");
            String keyId = "agent-" + agentKey + (reversePort == null ? "" : "-p" + reversePort);
            run(List.of("ssh-keygen", "-q", "-s", ca().toString(),
                    "-I", keyId,
                    "-n", settings.sshUser(),
                    "-V", "-5m:+" + Math.max(1, settings.validityHours()) + "h",
                    "-O", "clear", "-O", "permit-port-forwarding",
                    pub.toString()));
            return Files.readString(dir.resolve("agent-cert.pub")).trim();
        } catch (IOException e) {
            throw new IllegalStateException("인증서를 만들지 못했다: " + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 클러스터 Job 이 배스천으로 DB 터널(-L)만 여는 일회용 키와 인증서. 앱을 다른 클라우드로 옮길 때 DB 복사 Job 이 쓴다.
     * 역방향 포트는 주지 않는다. 에이전트 인증서와 key ID 가 겹치지 않게 {@code job-{name}} 으로 서명한다.
     *
     * @param name    Job 이름 ([a-z0-9-])
     * @param minutes 유효 시간
     */
    public JobKey jobKey(String name, int minutes) {
        if (!enabled()) {
            throw new IllegalStateException("DB 터널 CA 가 설정되지 않았다");
        }
        if (!name.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("Job 이름이 아니다: " + name);
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lily-job-key");
            Path key = dir.resolve("id");
            run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "job-" + name, "-f", key.toString()));
            run(List.of("ssh-keygen", "-q", "-s", ca().toString(),
                    "-I", "job-" + name,
                    "-n", settings.sshUser(),
                    "-V", "-5m:+" + Math.max(1, minutes) + "m",
                    "-O", "clear", "-O", "permit-port-forwarding",
                    dir.resolve("id.pub").toString()));
            return new JobKey(Files.readString(key), Files.readString(dir.resolve("id-cert.pub")).trim());
        } catch (IOException e) {
            throw new IllegalStateException("Job 키를 만들지 못했다: " + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 클러스터에 상주하는 DB 릴레이(-L 만)의 키와 인증서. 키는 처음 한 번 만들고, 인증서만 주기적으로 다시 서명한다
     * ({@link DatabaseRelay}). key ID 는 {@code relay-{name}}.
     *
     * @param privateKey 지금 쓰는 개인키. null 이면 새로 만든다
     * @param hours      인증서 유효 시간
     */
    public JobKey relayKey(String name, String privateKey, int hours) {
        if (!enabled()) {
            throw new IllegalStateException("DB 터널 CA 가 설정되지 않았다");
        }
        if (!name.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("릴레이 이름이 아니다: " + name);
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lily-relay-key");
            Path key = dir.resolve("id");
            if (privateKey == null || privateKey.isBlank()) {
                run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "relay-" + name, "-f", key.toString()));
            } else {
                Files.writeString(key, privateKey.trim() + "\n", StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(dir.resolve("id.pub"), output(List.of("ssh-keygen", "-y", "-f", key.toString())));
            }
            run(List.of("ssh-keygen", "-q", "-s", ca().toString(),
                    "-I", "relay-" + name,
                    "-n", settings.sshUser(),
                    "-V", "-5m:+" + Math.max(1, hours) + "h",
                    "-O", "clear", "-O", "permit-port-forwarding",
                    dir.resolve("id.pub").toString()));
            return new JobKey(Files.readString(key), Files.readString(dir.resolve("id-cert.pub")).trim());
        } catch (IOException e) {
            throw new IllegalStateException("릴레이 키를 만들지 못했다: " + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** @param privateKey OpenSSH 개인키 본문, @param certificate 그 공개키의 인증서 한 줄 */
    public record JobKey(String privateKey, String certificate) {
    }

    /** ssh-keygen 은 다른 사용자가 읽을 수 있는 개인키를 거절한다. 환경변수의 키를 소유자 전용 파일로 한 번 옮긴다 */
    private synchronized Path ca() throws IOException {
        if (caFile == null) {
            Path file = Files.createTempFile("lily-ca", ".key",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String pem = settings.caKey().replace("\\n", "\n").trim() + "\n";
            Files.writeString(file, pem, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            caFile = file;
        }
        return caFile;
    }

    private static void run(List<String> command) {
        output(command);
    }

    /** ssh-keygen 의 표준 출력 */
    private static String output(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ssh-keygen 이 끝나지 않았다");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ssh-keygen 실패: " + output.trim());
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("ssh-keygen 을 실행하지 못했다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 임시 디렉터리
        }
    }
}
