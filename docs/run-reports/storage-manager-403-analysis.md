# Storage-Manager 403 — PlatformJwtAuthFilter Blocks permitAll Paths

**Date:** 2026-04-16 16:55 UTC

## Problem

`StorageSecurityConfig` has `/api/v1/storage/**` as `permitAll()` but all inter-service calls return 403:
```
POST http://storage-manager:8096/api/v1/storage/store-stream → 403 ACCESS_DENIED
POST http://screening-service:8092/api/v1/screening/scan → 403
```

## Root Cause

`PlatformJwtAuthFilter` is added via `.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)`. This filter runs **before** Spring Security's `authorizeHttpRequests` evaluates the `permitAll()` rules. If the request has no JWT or SPIFFE token, the filter returns 403 — even though the endpoint would be permitted.

## Fix

`PlatformJwtAuthFilter` needs a skip-list for paths that are `permitAll()`:
```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/actuator/") 
        || path.startsWith("/api/v1/storage/")
        || path.startsWith("/health");
}
```

Or: don't send 403 from the filter — just skip setting the auth context and let Spring Security's authorization manager decide (it will allow `permitAll()` even without auth).

## Impact

All inter-service calls to storage-manager and screening-service fail with 403. This blocks:
- Step 1 CONVERT_EDI from storing converted output
- Step 0 SCREEN from calling screening-service scan
- INLINE promotion (VFS → storage-manager)
- AI engine classify calls
