#ifndef WCS_CLOUDFLARED_BRIDGE_H
#define WCS_CLOUDFLARED_BRIDGE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *wcs_tunnel_handle;

typedef enum {
    WCS_TUNNEL_STOPPED = 0,
    WCS_TUNNEL_STARTING = 1,
    WCS_TUNNEL_CONNECTED = 2,
    WCS_TUNNEL_RECONNECTING = 3,
    WCS_TUNNEL_FAILED = 4,
    WCS_TUNNEL_STOPPING = 5,
    WCS_TUNNEL_UNSUPPORTED = 6,
} wcs_tunnel_status_code;

typedef void (*wcs_callback)(void *user, int status, const char *url, const char *error);

wcs_tunnel_handle wcs_tunnel_start_quick(const char *origin, wcs_callback callback, void *user);
wcs_tunnel_handle wcs_tunnel_start_token(const char *token, const char *origin, wcs_callback callback, void *user);
int wcs_tunnel_begin_login(wcs_tunnel_handle handle, wcs_callback callback, void *user);
int wcs_tunnel_select_existing(wcs_tunnel_handle handle, const char *tunnel_id, const char *hostname);
int wcs_tunnel_stop(wcs_tunnel_handle handle);
int wcs_tunnel_status(wcs_tunnel_handle handle, char *buffer, size_t buffer_len);

#ifdef __cplusplus
}
#endif

#endif
