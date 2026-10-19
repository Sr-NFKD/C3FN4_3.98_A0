/* Upstream wire protocol on ESP-IDF's native NimBLE C API. */
#include "ble_service.h"
#include "ble_epaper.h"
#include <string.h>
#include "esp_log.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define UUID(n) BLE_UUID128_INIT(0x6c,0x61,0x63,0x64,0x70,0x65,0x20,0x9f, \
                                0x61,0x4c,0x6c,0x6a,n,0x00,0x4d,0x7b)
static const ble_uuid128_t service_uuid = UUID(1), control_uuid = UUID(2),
    notify_uuid = UUID(3), image_uuid = UUID(4), status_uuid = UUID(5);
static ble_proto_stream_t control_stream, image_stream;
static ble_gatt_t gatt;
static uint16_t connection = BLE_HS_CONN_HANDLE_NONE, notify_handle;
static uint8_t address_type;
static bool subscribed;

static void frame_received(const ble_frame_view_t *frame, void *ctx)
{
    bool image = ctx != NULL;
    if (image == (frame->type == BLE_CMD_PHOTO_DATA || frame->type == BLE_CMD_OTA_DATA))
        ble_gatt_dispatch_frame(frame);
}
static void reset_streams(void)
{
    ble_proto_stream_init(&control_stream, frame_received, NULL);
    ble_proto_stream_init(&image_stream, frame_received, (void *)1);
}
static int access_gatt(uint16_t conn, uint16_t attr,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn; (void)attr;
    int kind = (int)(intptr_t)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (kind == 5) {
            uint8_t status[20];
            ble_epaper_read_status(status);
            return os_mbuf_append(ctxt->om, status, sizeof(status)) == 0
                ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        return 0; /* Same initial empty readable values as upstream. */
    }
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR || (kind != 2 && kind != 4))
        return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    uint8_t bytes[512];
    uint16_t length = OS_MBUF_PKTLEN(ctxt->om);
    if (!length || length > sizeof(bytes) || os_mbuf_copydata(ctxt->om, 0, length, bytes))
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    ble_proto_stream_feed(kind == 2 ? &control_stream : &image_stream, bytes, length);
    return 0;
}

static const struct ble_gatt_svc_def services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &service_uuid.u,
     .characteristics = (struct ble_gatt_chr_def[]) {
         {.uuid = &control_uuid.u, .access_cb = access_gatt, .arg = (void *)2,
          .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE},
         {.uuid = &notify_uuid.u, .access_cb = access_gatt, .arg = (void *)3,
          .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY, .val_handle = &notify_handle},
         {.uuid = &image_uuid.u, .access_cb = access_gatt, .arg = (void *)4,
          .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE},
         {.uuid = &status_uuid.u, .access_cb = access_gatt, .arg = (void *)5,
          .flags = BLE_GATT_CHR_F_READ},
         {0}}},
    {0}
};

static int gap_event(struct ble_gap_event *event, void *arg);
static void advertise(void)
{
    struct ble_hs_adv_fields fields = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .uuids128 = (ble_uuid128_t *)&service_uuid,
        .num_uuids128 = 1, .uuids128_is_complete = 1};
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc) { ESP_LOGE("ble_service", "advertising fields failed: %d", rc); return; }
    const char *name = ble_svc_gap_device_name();
    struct ble_hs_adv_fields response = {
        .name = (uint8_t *)name, .name_len = strlen(name), .name_is_complete = 1};
    rc = ble_gap_adv_rsp_set_fields(&response);
    if (rc) { ESP_LOGE("ble_service", "scan response failed: %d", rc); return; }
    struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND, .disc_mode = BLE_GAP_DISC_MODE_GEN};
    rc = ble_gap_adv_start(address_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc) ESP_LOGE("ble_service", "advertising failed: %d", rc);
    else ESP_LOGI("ble_service", "advertising EPD-Calendar; service=%s", BLE_GATT_SERVICE_UUID);
}
static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    if (event->type == BLE_GAP_EVENT_CONNECT) {
        if (event->connect.status) advertise();
        else {
            reset_streams();
            connection = event->connect.conn_handle;
            subscribed = false;
            ble_gatt_set_connected(&gatt, true);
            ble_gatt_apply_connection_tx_limit(&gatt, connection);
            const struct ble_gap_upd_params params = {
                .itvl_min = 12, .itvl_max = 24, .latency = 0, .supervision_timeout = 400};
            ble_gap_update_params(connection, &params);
        }
    } else if (event->type == BLE_GAP_EVENT_DISCONNECT) {
        ble_epaper_disconnected();
        connection = BLE_HS_CONN_HANDLE_NONE;
        subscribed = false;
        ble_gatt_set_connected(&gatt, false);
        reset_streams();
        /* Upload session intentionally survives disconnect for PHOTO_BEGIN resume. */
        advertise();
    } else if (event->type == BLE_GAP_EVENT_SUBSCRIBE && event->subscribe.attr_handle == notify_handle) {
        subscribed = event->subscribe.cur_notify;
    } else if (event->type == BLE_GAP_EVENT_ADV_COMPLETE) advertise();
    return 0;
}
static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (!rc) rc = ble_hs_id_infer_auto(0, &address_type);
    if (rc) ESP_LOGE("ble_service", "BLE address initialization failed: %d", rc);
    else advertise();
}
static void host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
    nimble_port_freertos_deinit();
}
int ble_service_start(ble_gatt_rx_cb_t callback, void *ctx)
{
    if (!callback || nimble_port_init() != ESP_OK) return -1;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    reset_streams();
    if (ble_gatt_init(&gatt, callback, ctx) || ble_gatts_count_cfg(services) ||
        ble_gatts_add_svcs(services) || ble_svc_gap_device_name_set("EPD-Calendar")) return -1;
    ble_hs_cfg.sync_cb = on_sync;
    nimble_port_freertos_init(host_task);
    return 0;
}
int ble_service_notify(const uint8_t *frame, size_t length)
{
    uint16_t conn = connection;
    if (!frame || !length || conn == BLE_HS_CONN_HANDLE_NONE || !subscribed) return -1;
    uint16_t mtu = ble_att_mtu(conn);
    size_t max_chunk = mtu > 3 ? mtu - 3 : 20;
    if (max_chunk > 512) max_chunk = 512;
    for (size_t offset = 0; offset < length;) {
        size_t count = length - offset < max_chunk ? length - offset : max_chunk;
        struct os_mbuf *buffer = ble_hs_mbuf_from_flat(frame + offset, count);
        if (!buffer) return -2;
        if (ble_gatts_notify_custom(conn, notify_handle, buffer)) return -2;
        offset += count;
        if (offset < length) vTaskDelay(1);
    }
    return 0;
}
