package com.ops.deploy.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void ok_shouldReturnZeroCodeAndData() {
        Result<String> r = Result.ok("pong");

        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getMessage()).isEqualTo("ok");
        assertThat(r.getData()).isEqualTo("pong");
    }

    @Test
    void fail_shouldReturnGivenCodeMessageAndNullData() {
        Result<Object> r = Result.fail(ErrorCode.NOT_FOUND, "not found");

        assertThat(r.getCode()).isEqualTo(404);
        assertThat(r.getMessage()).isEqualTo("not found");
        assertThat(r.getData()).isNull();
    }
}
