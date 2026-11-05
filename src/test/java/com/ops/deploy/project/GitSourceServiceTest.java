package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GitSourceService 单测：用本地 bare 仓库当远端（file:// 协议），验证
 * clone → 打包 → 目录识别 → commit 冻结的全链路（无需网络与 git CLI）。
 * 同时覆盖 URL 校验（非法 scheme → 400）与失败不落盘。
 */
class GitSourceServiceTest {

    @TempDir
    Path tmp;

    private GitSourceService service;
    private TypeDetector detector;
    private PackageStorage storage;

    @BeforeEach
    void setUp() {
        detector = new TypeDetector();
        storage = new PackageStorage(tmp.resolve("data"));
        service = new GitSourceService(detector, storage);
    }

    /** 造一个本地 bare 仓库，含一次 commit（指定文件 → 供类型识别）。 */
    private Path initBareRepo(String name, String fileName, String content) throws Exception {
        Path work = tmp.resolve(name + "-work");
        Files.createDirectories(work);
        Files.writeString(work.resolve(fileName), content);
        Path bareDir = tmp.resolve(name + ".git");
        Git.init().setBare(true).setDirectory(bareDir.toFile()).call().close();
        disableAutoGc(bareDir);
        try (Git g = Git.init().setDirectory(work.toFile()).call()) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setSign(false)
                    .setAuthor(new PersonIdent("t", "t@t")).call();
            // 把 work 仓库分支推入 bare（否则 bare 是空仓库，clone 会失败）
            pushToBare(bareDir, work);
        }
        return bareDir;
    }

    /** 禁用 JGit 后台 auto-gc（否则 close 仓库时启动的 GC 线程持有 pack 文件，@TempDir 清理失败）。 */
    private static void disableAutoGc(Path bareDir) throws Exception {
        org.eclipse.jgit.storage.file.FileBasedConfig cfg =
                new org.eclipse.jgit.storage.file.FileBasedConfig(
                        bareDir.resolve("config").toFile(), org.eclipse.jgit.util.FS.DETECTED);
        cfg.setString("core", null, "autogc", "0");
        cfg.setString("gc", null, "auto", "0");
        cfg.save();
    }

    /** 把 work 仓库的 refs/heads/* 推入 bare（底层 Transport fetch，JGit 6 API 稳定）。 */
    private static void pushToBare(Path bareDir, Path work) throws Exception {
        try (org.eclipse.jgit.lib.Repository bareRepo = new org.eclipse.jgit.lib.RepositoryBuilder()
                     .setGitDir(bareDir.toFile()).build()) {
            try (org.eclipse.jgit.transport.Transport t =
                         org.eclipse.jgit.transport.Transport.open(
                                 bareRepo, work.toFile().toURI().toString())) {
                t.fetch(new org.eclipse.jgit.lib.EmptyProgressMonitor() {
                },
                        java.util.List.of(new org.eclipse.jgit.transport.RefSpec("refs/heads/*:refs/heads/*")));
            }
        }
    }

    @Test
    void cloneLocalBareRepo_shouldZipDetectAndFreezeCommit() throws Exception {
        Path bare = initBareRepo("demo", "package.json",
                "{\n  \"name\": \"demo\",\n  \"scripts\": { \"start\": \"node index.js\" }\n}\n");

        GitSourceService.Result r = service.fetch(pureGitUrl(bare), null, null, null);

        assertThat(r.commitId()).isNotBlank().hasSize(40);
        // 冻结的 commit 即 bare 仓库 HEAD（直接读文件，避免 JGit open 触发后台 GC 线程锁文件）
        assertThat(r.commitId()).isEqualTo(bareHead(bare));
        // 打包产物：zip 文件名约定 {仓库basename}.git.zip，临时文件已算好 sha
        assertThat(r.temp()).isNotNull();
        assertThat(r.temp().getFileName()).isEqualTo("demo.git.zip");
        assertThat(r.temp().getSha256()).hasSize(64);
        assertThat(Files.size(r.temp().getTempPath())).isGreaterThan(0);
        // 识别走目录树（clone 出的源码），识别结果带 git 来源证据
        assertThat(r.detection().getDetectedType()).isEqualTo("NODE");
        assertThat(r.detection().getEvidence().toString()).contains("git");
    }

    @Test
    void cloneWithBranch_shouldCheckoutSpecifiedBranch() throws Exception {
        // 主分支 + feature 分支（不同 package.json 内容以区分）
        Path work = tmp.resolve("br-work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("package.json"), "{\"name\":\"main\"}\n");
        Path bareDir = tmp.resolve("br.git");
        Git.init().setBare(true).setDirectory(bareDir.toFile()).call().close();
        disableAutoGc(bareDir);
        try (Git g = Git.init().setDirectory(work.toFile()).call()) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("main").setSign(false)
                    .setAuthor(new PersonIdent("t", "t@t")).call();
            // feature 分支之外的本地分支即默认分支（动态识别，避免写死 master/main）
            g.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(work.resolve("package.json"), "{\"name\":\"feature\"}\n");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("feature").setSign(false)
                    .setAuthor(new PersonIdent("t", "t@t")).call();
            // 把两个分支推入 bare
            pushToBare(bareDir, work);
        }

        GitSourceService.Result r = service.fetch(pureGitUrl(bareDir), "feature", null, null);

        // feature 分支的 commit（非默认分支 HEAD）——直接读 bare 仓库文件
        String defaultHead = bareBranchHead(bareDir, bareDefaultBranch(bareDir));
        String featureHead = bareBranchHead(bareDir, "feature");
        assertThat(r.commitId()).isEqualTo(featureHead);
        assertThat(r.commitId()).isNotEqualTo(defaultHead);
        assertThat(r.temp().getFileName()).isEqualTo("br.git.zip");
    }

    @Test
    void invalidUrl_shouldThrowBadRequest() throws Exception {
        PackageStorage spy = mock(PackageStorage.class);
        GitSourceService plain = new GitSourceService(detector, spy);
        assertThatThrownBy(() -> plain.fetch("ftp://git.example.com/repo.git", null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> plain.fetch("  ", null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // URL 校验在 clone 前失败 → 从未触碰存储
        verify(spy, never()).storeTemp(any(), any());
    }

    @Test
    void cloneFailure_shouldThrowBadRequestAndNotStore() throws Exception {
        // 指向不存在路径的 file:// URL → clone 失败
        Path missing = tmp.resolve("no-such-repo.git");
        assertThatThrownBy(() -> service.fetch(pureGitUrl(missing), null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // 失败不落盘：不产生任何临时文件（.tmp 目录为空或不存在）
        Path tmpDir = tmp.resolve("data").resolve(".tmp");
        if (Files.exists(tmpDir)) {
            try (var s = Files.list(tmpDir)) {
                assertThat(s.count()).isEqualTo(0);
            }
        }
    }

    /** file:// URL（JGit 支持本地 file 传输，等价于远端 clone）。 */
    private String pureGitUrl(Path bareRepoDir) {
        return bareRepoDir.toAbsolutePath().toUri().toString() + "/";
    }

    /** bare 仓库 HEAD 指向的 commit SHA（直接读文件，不依赖 JGit 打开仓库）。 */
    private static String bareHead(Path bareDir) throws Exception {
        String head = Files.readString(bareDir.resolve("HEAD")).trim();
        if (head.startsWith("ref:")) {
            return bareBranchHead(bareDir, head.substring(4).trim()
                    .replaceFirst("^refs/heads/", ""));
        }
        return head;
    }

    /** bare 仓库默认分支名（HEAD 符号引用指向的分支）。 */
    private static String bareDefaultBranch(Path bareDir) throws Exception {
        String head = Files.readString(bareDir.resolve("HEAD")).trim();
        if (head.startsWith("ref:")) {
            return head.substring(4).trim().replaceFirst("^refs/heads/", "");
        }
        throw new IllegalStateException("bare repo HEAD is detached: " + head);
    }

    /** 读 bare 仓库某分支的 commit SHA（loose ref 优先，回退 packed-refs）。 */
    private static String bareBranchHead(Path bareDir, String branch) throws Exception {
        Path loose = bareDir.resolve("refs").resolve("heads").resolve(branch);
        if (Files.exists(loose)) {
            return Files.readString(loose).trim();
        }
        Path packed = bareDir.resolve("packed-refs");
        if (!Files.exists(packed)) {
            throw new IllegalStateException("branch not found in bare repo: " + branch);
        }
        String key = "refs/heads/" + branch;
        for (String line : Files.readAllLines(packed)) {
            if (line.startsWith("#") || line.isEmpty() || line.startsWith("^")) {
                continue;
            }
            int sp = line.indexOf(' ');
            if (sp > 0 && line.substring(sp + 1).equals(key)) {
                return line.substring(0, sp);
            }
        }
        throw new IllegalStateException("branch not found in bare repo: " + branch);
    }
}
