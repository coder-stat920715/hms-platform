package com.hms.patient.config;

import com.hms.patient.security.HmsPermissionEvaluator;
import java.io.Serializable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.core.Authentication;

@Configuration
public class MethodSecurityConfig {

  /**
   * Static + lazy: method-security infrastructure is created very early; resolving the evaluator on first use
   * keeps its dependencies (cache proxies, repositories) eligible for normal post-processing.
   */
  @Bean
  static MethodSecurityExpressionHandler methodSecurityExpressionHandler(ObjectProvider<HmsPermissionEvaluator> provider) {
    var handler = new DefaultMethodSecurityExpressionHandler();
    handler.setPermissionEvaluator(new PermissionEvaluator() {
      @Override
      public boolean hasPermission(Authentication a, Object target, Object permission) {
        return provider.getObject().hasPermission(a, target, permission);
      }

      @Override
      public boolean hasPermission(Authentication a, Serializable id, String type, Object permission) {
        return provider.getObject().hasPermission(a, id, type, permission);
      }
    });
    return handler;
  }
}
