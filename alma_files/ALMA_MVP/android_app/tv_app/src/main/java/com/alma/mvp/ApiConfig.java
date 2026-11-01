package com.alma.mvp;

public final class ApiConfig {
    private ApiConfig() {}

    // Development default. For a physical phone, replace with the reachable
    // backend HTTPS URL. No AI provider key belongs in this Android project.
    public static final String BASE_URL = BuildConfig.ALMA_BASE_URL;
}
