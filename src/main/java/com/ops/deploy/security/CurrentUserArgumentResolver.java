package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@code @CurrentUser AuthUser} 控制器参数：从 request attribute 读取
 * （由 {@link JwtAuthFilter} 注入）。未认证时 attribute 为 null，抛 401。
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && AuthUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Object attr = webRequest.getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE,
                NativeWebRequest.SCOPE_REQUEST);
        if (attr == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "unauthorized");
        }
        return attr;
    }
}
