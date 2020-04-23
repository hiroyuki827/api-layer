/*
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copyright Contributors to the Zowe Project.
 */

package org.zowe.apiml.gateway.ribbon.http;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.loadbalancer.reactive.ExecutionListener;
import com.netflix.zuul.context.RequestContext;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zowe.apiml.gateway.security.service.AuthenticationService;
import org.zowe.apiml.gateway.security.service.ServiceAuthenticationServiceImpl;
import org.zowe.apiml.gateway.security.service.schema.AuthenticationCommand;
import org.zowe.apiml.gateway.security.service.schema.ServiceAuthenticationService;
import org.zowe.apiml.security.common.auth.Authentication;
import org.zowe.apiml.security.common.token.TokenAuthentication;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.zowe.apiml.gateway.ribbon.ApimlZoneAwareLoadBalancer.LOADBALANCED_INSTANCE_INFO_KEY;

class ServiceAuthenticationDecoratorTest {

    private static final String AUTHENTICATION_COMMAND_KEY = "zoweAuthenticationCommand";

    ServiceAuthenticationService serviceAuthenticationService = mock(ServiceAuthenticationService.class);
    AuthenticationService authenticationService = mock(AuthenticationService.class);
    InstanceInfo info = InstanceInfo.Builder.newBuilder().setInstanceId("instanceid").setAppName("appname").build();

    ServiceAuthenticationDecorator decorator;
    HttpRequest request;

    @BeforeEach
    void setUp() {
       decorator = new ServiceAuthenticationDecorator(serviceAuthenticationService, authenticationService);
       request = new HttpGet("/");
       RequestContext.getCurrentContext().clear();
    }

    @Test
    void givenContextWithoutCommand_whenProcess_thenNoAction() throws RequestAbortException {
        HttpRequest request = new HttpGet("/");
        decorator.process(request);
        verify(serviceAuthenticationService, never()).getAuthenticationCommand(any(Authentication.class), any());
        verify(serviceAuthenticationService, never()).getAuthenticationCommand(any(String.class), any());
    }

    @Test
    void givenContextWithCorrectKey_whenProcess_thenShouldRetrieveCommand() throws RequestAbortException {
        AuthenticationCommand universalCmd = mock(ServiceAuthenticationServiceImpl.UniversalAuthenticationCommand.class);
        AuthenticationCommand cmd = getAuthenticationCommand(universalCmd);
        doReturn(TokenAuthentication.createAuthenticated("user", "jwtToken")).when(authenticationService).validateJwtToken("jwtToken");

        decorator.process(request);
        verify(serviceAuthenticationService, atLeastOnce()).getAuthentication(info);
        verify(cmd, times(1)).applyToRequest(request);
    }

    @Test
    void givenContextWithoutInstanceInfo_whenProcess_thenShouldThrowRequestStoppingException() {
        AuthenticationCommand universalCmd = mock(ServiceAuthenticationServiceImpl.UniversalAuthenticationCommand.class);
        RequestContext.getCurrentContext().set(AUTHENTICATION_COMMAND_KEY, universalCmd);
        assertThrows(RequestContextNotPreparedException.class, () -> decorator.process(request),
            "Should fail on RequestContext without InstanceInfo set by LoadBalancer impl.");
    }

    @Test
    void givenContextWithCorrectKey_whenProcess_thenShouldRetrieve() {
        AuthenticationCommand universalCmd = mock(ServiceAuthenticationServiceImpl.UniversalAuthenticationCommand.class);
        AuthenticationCommand cmd = getAuthenticationCommand(universalCmd);
        TokenAuthentication tokenAuthentication = mock(TokenAuthentication.class);
        doReturn(tokenAuthentication).when(authenticationService).validateJwtToken("jwtToken");
        when(authenticationService.validateJwtToken("jwtToken").isAuthenticated()).thenReturn(false);

        assertThrows(ExecutionListener.AbortExecutionException.class, () ->
            decorator.process(request),
            "Exception is not BadCredentialsException");
        verify(cmd, times(0)).applyToRequest(request);
    }

    private AuthenticationCommand getAuthenticationCommand(AuthenticationCommand universalCmd) {

        RequestContext.getCurrentContext().set(AUTHENTICATION_COMMAND_KEY, universalCmd);
        RequestContext.getCurrentContext().set(LOADBALANCED_INSTANCE_INFO_KEY, info);
        doReturn(Optional.of("jwtToken")).when(authenticationService).getJwtTokenFromRequest(any());
        Authentication authentication = mock(Authentication.class);
        AuthenticationCommand cmd = mock(AuthenticationCommand.class);

        when(serviceAuthenticationService.getAuthentication(info)).thenReturn(authentication);
        when(serviceAuthenticationService.getAuthenticationCommand(authentication, "jwtToken")).thenReturn(cmd);
        doReturn(true).when(cmd).isRequiredValidJwt();
        return cmd;
    }
}