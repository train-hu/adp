package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * git 源码拉取打包服务（上传部署包支持 git 地址）。
 *
 * <p>流程：校验 URL（https/http/file 白名单）→ JGit clone 到临时目录 → 记录
 * HEAD commit（版本冻结点，部署复用该 zip）→ 排除 .git 打 zip → 经
 * {@link PackageStorage#storeTemp}（sha 去重）入库 → 对 clone 目录树跑
 * {@link TypeDetector#detect} 做类型识别（evidence 追加 git 来源信息）。
 *
 * <p>任何一步失败统一抛 {@link ErrorCode#BAD_REQUEST}，并清理临时目录/临时文件，
 * 不产生脏数据。
 */
@Component
public class GitSourceService {

    private static final Logger log = LoggerFactory.getLogger(GitSourceService.class);

    private final TypeDetector detector;
    private final PackageStorage storage;

    public GitSourceService(TypeDetector detector, PackageStorage storage) {
        this.detector = detector;
        this.storage = storage;
    }

    /** 拉取结果：冻结的 commit SHA + 类型识别结果 + 待落位的临时文件（ProjectService 落库后 moveIntoPlace）。 */
    public static class Result {
        private final String commitId;
        private final DetectedResult detection;
        private final PackageStorage.TempFile temp;

        public Result(String commitId, DetectedResult detection, PackageStorage.TempFile temp) {
            this.commitId = commitId;
            this.detection = detection;
            this.temp = temp;
        }

        public String commitId() {
            return commitId;
        }

        public DetectedResult detection() {
            return detection;
        }

        public PackageStorage.TempFile temp() {
            return temp;
        }
    }

    /**
     * 从 git 仓库拉取源码并打包为 zip 入库。
     *
     * @param gitUrl   仓库地址（https/http/file 协议）
     * @param gitRef   分支/标签，可空（空则用默认分支 HEAD）
     * @param username 私有仓库用户名，可空
     * @param password 私有仓库密码/令牌，可空
     * @return 冻结 commit + 识别结果
     */
    public Result fetch(String gitUrl, String gitRef, String username, String password) {
        String url = validateUrl(gitUrl);
        Path workDir = null;
        PackageStorage.TempFile temp = null;
        try {
            workDir = Files.createTempDirectory("git-src-");

            try (Git git = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(workDir.toFile())
                    .setCredentialsProvider(credentials(username, password))
                    .setTimeout(600)
                    .call()) {
                // 指定分支/标签：clone 后 HEAD 在默认分支，其他分支只是 origin/* 跟踪引用，
                // 先解析出 commit SHA 再 checkout（分支/标签/远程分支统一处理）
                if (gitRef != null && !gitRef.isBlank()) {
                    org.eclipse.jgit.lib.ObjectId target =
                            resolveRef(git.getRepository(), gitRef.trim());
                    if (target == null) {
                        throw new BizException(ErrorCode.BAD_REQUEST,
                                "git 分支/标签不存在: " + gitRef.trim());
                    }
                    git.checkout()
                            .setName(gitRef.trim())
                            .setStartPoint(target.getName())
                            .setCreateBranch(true)
                            .setForce(true)
                            .call();
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "git clone 失败: " + rootMessage(e));
            }

            String commitId = headCommit(workDir);

            // 排除 .git 打 zip，经 storeTemp 计算 sha（同内容去重复用）
            String zipName = zipNameOf(url);
            Path zipPath = null;
            try {
                zipPath = Files.createTempFile("git-src-zip-", ".zip");
                zipDirectory(workDir, zipPath);
                try (InputStream in = Files.newInputStream(zipPath)) {
                    temp = storage.storeTemp(zipName, in);
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "git 源码打包失败: " + rootMessage(e));
            } finally {
                deleteQuietly(zipPath);
            }

            // 识别走 clone 出的源码目录树（与上传归档解压后的识别同路径）
            DetectedResult detection = detector.detect(workDir);
            detection.addEvidence("git:" + url + " @ " + commitId
                    + (gitRef != null && !gitRef.isBlank() ? " (" + gitRef.trim() + ")" : ""));

            log.info("git source fetched: {} commit={} type={}", url, commitId,
                    detection.getDetectedType());
            return new Result(commitId, detection, temp);
        } catch (BizException e) {
            // 失败清理：临时文件 + 临时目录
            if (temp != null) {
                storage.deleteTemp(temp);
            }
            deleteDirQuietly(workDir);
            throw e;
        } catch (Exception e) {
            if (temp != null) {
                storage.deleteTemp(temp);
            }
            deleteDirQuietly(workDir);
            throw new BizException(ErrorCode.BAD_REQUEST, "git 源码拉取失败: " + rootMessage(e));
        }
    }

    /** 解析 ref（分支/标签/远程跟踪分支/完整 SHA）→ commit ObjectId，不存在返回 null。 */
    private static org.eclipse.jgit.lib.ObjectId resolveRef(
            org.eclipse.jgit.lib.Repository repo, String ref) {
        try {
            String[] candidates = {
                    "refs/heads/" + ref,
                    "refs/tags/" + ref,
                    "refs/remotes/origin/" + ref,
                    ref
            };
            for (String c : candidates) {
                org.eclipse.jgit.lib.ObjectId id = repo.resolve(c);
                if (id != null) {
                    return id;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 仓库名：URL 最后一段，去 .git 后缀再补 .git.zip（如 demo.git → demo.git.zip）。 */
    static String zipNameOf(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        String base = u;
        try {
            base = URI.create(u).getPath() == null ? u : URI.create(u).getPath();
        } catch (IllegalArgumentException ignore) {
            // 非标准 URL 直接用原串
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.isEmpty()) {
            base = "repo";
        }
        String lower = base.toLowerCase();
        if (lower.endsWith(".git")) {
            return base + ".zip";
        }
        return base + ".git.zip";
    }

    private static String validateUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "git 地址不能为空");
        }
        String url = gitUrl.trim();
        String scheme = "";
        int colon = url.indexOf(':');
        int slash = url.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash)) {
            scheme = url.substring(0, colon).toLowerCase();
        }
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"file".equals(scheme)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "git 地址仅支持 https/http/file 协议: " + gitUrl);
        }
        return url;
    }

    private static CredentialsProvider credentials(String username, String password) {
        if (username != null && !username.isBlank()) {
            return new UsernamePasswordCredentialsProvider(
                    username.trim(), password == null ? "" : password);
        }
        return null;
    }

    /** clone 目录的 HEAD commit SHA。 */
    private static String headCommit(Path workDir) {
        try (org.eclipse.jgit.lib.Repository repo =
                     new org.eclipse.jgit.lib.RepositoryBuilder()
                             .setGitDir(workDir.resolve(".git").toFile()).build()) {
            org.eclipse.jgit.lib.ObjectId head = repo.resolve("HEAD");
            if (head == null || head.getName().length() != 40) {
                throw new BizException(ErrorCode.BAD_REQUEST, "git 仓库为空，无 commit 可拉取");
            }
            return head.getName();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "git 仓库读取失败: " + e.getMessage());
        }
    }

    /** 把目录树打成 zip（入口为相对路径，排除 .git）。 */
    private static void zipDirectory(Path dir, Path zipPath) throws IOException {
        Files.deleteIfExists(zipPath);
        try (OutputStream fout = Files.newOutputStream(zipPath);
             ZipOutputStream zout = new ZipOutputStream(fout);
             Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                 .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                 .forEach(p -> {
                     Path rel = dir.relativize(p);
                     String first = rel.getName(0).toString();
                     if (first.equals(".git")) {
                         return;
                     }
                     String entry = rel.toString().replace('\\', '/');
                     try {
                         zout.putNextEntry(new ZipEntry(entry));
                         Files.copy(p, zout);
                         zout.closeEntry();
                     } catch (IOException e) {
                         throw new RuntimeException(e);
                     }
                 });
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignore) {
                // 尽力清理
            }
        }
    }

    private static void deleteDirQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 尽力清理
                }
            });
        } catch (IOException e) {
            log.warn("failed to cleanup git temp dir: {}", dir, e);
        }
    }
}
