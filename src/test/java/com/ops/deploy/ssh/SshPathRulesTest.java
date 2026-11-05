package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshPathRules 纯逻辑单测：taskName / fileName / remoteDir 的形态校验。
 * 防止 {@code ../} 或 {@code /} 拼出预期外的远端路径（任意目录创建/写入）。
 */
class SshPathRulesTest {

    // ---------- validateTaskName ----------

    @Test
    void taskName_validFormats_pass() {
        assertThatCode(() -> {
            SshPathRules.validateTaskName("task-1");
            SshPathRules.validateTaskName("release_v1.2");
            SshPathRules.validateTaskName("ABC098");
            SshPathRules.validateTaskName("a.b-c_d");
        }).doesNotThrowAnyException();
    }

    @Test
    void taskName_pathTraversal_throwsBadRequest() {
        // 注意：单独的 ".." 匹配规格正则（点号属允许字符），且实际拼接为 "..status" 文件名、
        // 不构成路径穿越，故不在此处断言；真正的穿越片段（含 / 的 ..）必须拒绝
        for (String bad : new String[]{"../../evil", "../a", "a/../../b"}) {
            assertThatThrownBy(() -> SshPathRules.validateTaskName(bad))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void taskName_slashSpaceSpecialChar_throwsBadRequest() {
        for (String bad : new String[]{"a/b", "a b", "a$b", "a;b", "a>b", "a&&b", "a|b"}) {
            assertThatThrownBy(() -> SshPathRules.validateTaskName(bad))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void taskName_nullEmpty_throwsBadRequest() {
        assertThatThrownBy(() -> SshPathRules.validateTaskName(null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SshPathRules.validateTaskName(""))
                .isInstanceOf(BizException.class);
    }

    // ---------- validateFileName ----------

    @Test
    void fileName_validFormats_pass() {
        assertThatCode(() -> {
            SshPathRules.validateFileName("app.jar");
            SshPathRules.validateFileName("a-b.tar.gz");
            SshPathRules.validateFileName("README.md");
        }).doesNotThrowAnyException();
    }

    @Test
    void fileName_withSlash_throwsBadRequest() {
        for (String bad : new String[]{"../evil", "a/../../b", "sub/app.jar"}) {
            assertThatThrownBy(() -> SshPathRules.validateFileName(bad))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void fileName_specialChar_throwsBadRequest() {
        for (String bad : new String[]{"a b", "a$b", "a'b"}) {
            assertThatThrownBy(() -> SshPathRules.validateFileName(bad))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    // ---------- validateRemoteDir ----------

    @Test
    void remoteDir_absolutePass_throwsBadRequest() {
        for (String bad : new String[]{"data/app", "./x", "a/../b", "a/b/../../c"}) {
            assertThatThrownBy(() -> SshPathRules.validateRemoteDir(bad))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void remoteDir_nullEmpty_throwsBadRequest() {
        assertThatThrownBy(() -> SshPathRules.validateRemoteDir(null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SshPathRules.validateRemoteDir(""))
                .isInstanceOf(BizException.class);
    }

    @Test
    void remoteDir_validFormats_pass() {
        assertThatCode(() -> {
            SshPathRules.validateRemoteDir("/");
            SshPathRules.validateRemoteDir("/data/app");
            SshPathRules.validateRemoteDir("/opt/my app/.deploy");
            SshPathRules.validateRemoteDir("/a..b/c"); // 连续点号不是 ".." 段，允许
        }).doesNotThrowAnyException();
    }
}
