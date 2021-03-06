/*
 * Autosleep
 * Copyright (C) 2016 Orange
 * Authors: Benjamin Einaudi   benjamin.einaudi@orange.com
 *          Arnaud Ruffin      arnaud.ruffin@orange.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.autosleep.ui.proxy;

import lombok.extern.slf4j.Slf4j;
import org.cloudfoundry.autosleep.access.cloudfoundry.CloudFoundryApi;
import org.cloudfoundry.autosleep.access.cloudfoundry.CloudFoundryException;
import org.cloudfoundry.autosleep.access.dao.model.ProxyMapEntry;
import org.cloudfoundry.autosleep.access.dao.repositories.ProxyMapEntryRepository;
import org.cloudfoundry.autosleep.config.Config;
import org.cloudfoundry.autosleep.config.Config.CloudFoundryAppState;
import org.cloudfoundry.autosleep.util.TimeManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestOperations;
import org.springframework.web.servlet.HandlerMapping;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RestController
@Slf4j
public class WildcardProxy {

    static final String HEADER_FORWARDED = "CF-Autosleep-Proxy-Signature";

    static final String HEADER_HOST = "host";

    static final String HEADER_PROTOCOL = "x-forwarded-proto";

    private final RestOperations restOperations;

    protected String proxySignature;

    private String autosleepHost;

    @Autowired
    private CloudFoundryApi cfApi;

    @Autowired
    private Environment env;

    @Autowired
    private ProxyMapEntryRepository proxyMap;

    @Autowired
    private TimeManager timeManager;

    @Autowired
    WildcardProxy(RestOperations restOperations) {
        this.restOperations = restOperations;
    }

    private RequestEntity<?> getOutgoingRequest(RequestEntity<?> incoming, URI destination) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(incoming.getHeaders());
        //add custom header with our signature, to identify our own forwarded traffic
        headers.put(HEADER_FORWARDED, Collections.singletonList(proxySignature));
        return new RequestEntity<>(incoming.getBody(), headers, incoming.getMethod(), destination);
    }

    @PostConstruct
    void init() throws Exception {
        //not stored in Config, because this impl is temporary
        String securityPass = env.getProperty("security.user.password");
        autosleepHost = null;
        autosleepHost = InetAddress.getLocalHost().getHostName();
        this.proxySignature = Arrays.toString(MessageDigest.getInstance("MD5").digest((autosleepHost
                + securityPass).getBytes("UTF-8")));

    }

    @RequestMapping(headers = {HEADER_PROTOCOL, HEADER_HOST})
    ResponseEntity<?> proxify(@RequestHeader(HEADER_HOST) String targetHost,
                              RequestEntity<byte[]> incoming,
                              HttpServletRequest request) throws InterruptedException {

        List<String> alreadyForwardedHeader = incoming.getHeaders().get(HEADER_FORWARDED);
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String protocol = incoming.getHeaders().get(HEADER_PROTOCOL).get(0);

        log.info("Incoming Request for route : {} path: {}", targetHost, path);

        if (alreadyForwardedHeader != null && proxySignature.equals(alreadyForwardedHeader.get(0))) {
            log.error("We've already forwarded this traffic, this should not happen");
            return new ResponseEntity<Object>("Infinite loop forwarding error", HttpStatus.INTERNAL_SERVER_ERROR);
        } else {
            ProxyMapEntry mapEntry = proxyMap.findOne(targetHost);

            if (mapEntry == null) {
                return new ResponseEntity<Object>("Sorry, but this page doesn't exist!", HttpStatus.NOT_FOUND);
            } else if (mapEntry.isRestarting()) {
                return new ResponseEntity<Object>("Autosleep is restarting, please retry in few seconds",
                        HttpStatus.SERVICE_UNAVAILABLE);
            } else {
                mapEntry.setRestarting(true);

                proxyMap.save(mapEntry);
                try {
                    String appId = mapEntry.getAppId();
                    if (!CloudFoundryAppState.STARTED.equals(cfApi.getApplicationState(appId))) {
                        cfApi.startApplication(appId);
                        timeManager.sleep(Config.PERIOD_BETWEEN_STATE_CHECKS_DURING_RESTART);
                    }
                    while (!CloudFoundryAppState.STARTED.equals(cfApi.getApplicationState(appId))) {
                        log.debug("waiting for app {} restart...", appId);
                        timeManager.sleep(Config.PERIOD_BETWEEN_STATE_CHECKS_DURING_RESTART);
                        //TODO add timeout that would log error and reset mapEntry.isStarting to false
                    }
                    proxyMap.delete(mapEntry);

                } catch (CloudFoundryException e) {
                    log.error("Couldn't launch app restart", e);
                    mapEntry.setRestarting(false);
                    proxyMap.save(mapEntry);
                }
            }
        }

        URI uri = URI.create(protocol + "://" + targetHost + path);
        RequestEntity<?> outgoing = getOutgoingRequest(incoming, uri);
        log.info("Outgoing Request: {}", outgoing);

        //if "outgoing" point to a 404, this will trigger a 500. Is this really a pb?
        return this.restOperations.exchange(outgoing, byte[].class);
    }

}
