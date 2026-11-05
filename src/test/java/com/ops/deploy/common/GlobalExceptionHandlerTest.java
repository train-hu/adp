package com.ops.deploy.common;

import com.ops.deploy.web.PingController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        // 仅挂载异常处理器与一个会抛异常的测试控制器，避免加载 Spring 上下文 / 连库
        handler = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void handleValidationException_returns400WithFieldMessages() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("serverCreateRequest", "name", null, false,
                        new String[] { "NotBlank" }, null, "must not be blank"),
                new FieldError("serverCreateRequest", "password", "", false,
                        new String[] { "NotBlank" }, null, "must not be blank")));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(
                mock(MethodParameter.class), bindingResult);

        ResponseEntity<Result<Void>> response = handler.handleValidationException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("name");
        assertThat(response.getBody().getMessage()).contains("must not be blank");
        assertThat(response.getBody().getMessage()).contains("password");
    }

    @Test
    void handleValidationException_noFieldErrors_returns400Generic() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(
                mock(MethodParameter.class), bindingResult);

        ResponseEntity<Result<Void>> response = handler.handleValidationException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("invalid request");
    }

    @Test
    void invalidRequestBody_shouldReturn400WithFieldMessages() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(new InvalidRequestController())
                .setControllerAdvice(handler)
                .setValidator(new LocalValidatorFactoryBean())
                .build();

        mockMvc.perform(post("/invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("name")));
    }

    @Test
    void bizException_notFound_shouldReturn404StatusAndBodyCode404() throws Exception {
        mockMvc.perform(get("/throw/{code}", 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("no such resource"));
    }

    @Test
    void bizException_unauthorized_shouldReturn401StatusAndBodyCode401() throws Exception {
        mockMvc.perform(get("/throw/{code}", 401))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("unauthorized"));
    }

    @Test
    void bizException_forbidden_shouldReturn403StatusAndBodyCode403() throws Exception {
        mockMvc.perform(get("/throw/{code}", 403))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    @Test
    void bizException_businessCode_shouldReturn200StatusButNonZeroBodyCode() throws Exception {
        mockMvc.perform(get("/throw/{code}", 5001))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001))
                .andExpect(jsonPath("$.message").value("deploy failed"));
    }

    @Test
    void unexpectedException_shouldReturn500BodyAnd200Status() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("internal server error"));
    }

    @Test
    void ping_shouldReturnOkResult() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(new PingController())
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(get("/api/ping").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("pong"));
    }

    @RestController
    @RequestMapping("/throw")
    static class ThrowingController {

        @GetMapping("/{code}")
        public String throwBiz(@org.springframework.web.bind.annotation.PathVariable int code) {
            String message = switch (code) {
                case 404 -> "no such resource";
                case 401 -> "unauthorized";
                case 403 -> "forbidden";
                case 5001 -> "deploy failed";
                default -> "error " + code;
            };
            throw new BizException(code, message);
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("kaboom");
        }
    }

    /** 带 @Valid 的测试端点，用于验证 @Valid 失败走 400 handler。 */
    @RestController
    static class InvalidRequestController {

        @PostMapping("/invalid")
        public String create(@Valid @RequestBody CreateRequest req) {
            return "ok";
        }
    }

    static class CreateRequest {
        @NotBlank
        private String name;
    }
}
