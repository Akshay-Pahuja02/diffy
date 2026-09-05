package ai.diffy.lifter;

import ai.diffy.proxy.HttpResponse;

public record LiftResponseInput(HttpResponse response, String requestPath) {}
