/* Contract checks against upstream commit 779eab2; no hardware required. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "ble/ble_protocol.h"
#include "ble/ble_commands.h"
static ble_commands_t commands;
static nvs_store_config_t config;
static int persistence_failure, refreshes, wifi_applied, weather_requests, frames;
static uint16_t seq;
static int ota_calls;
static int firmware_command(void *ctx,const ble_frame_view_t *frame,uint8_t *reply,uint16_t *length) {
    (void)ctx; ++ota_calls; reply[0]=frame->type; *length=1; return BLE_COMMAND_OK;
}
static esp_err_t persist(const nvs_store_config_t *c) { (void)c; return persistence_failure ? -1 : ESP_OK; }
static void changed(void *ctx) { (void)ctx; ++refreshes; }
static int runtime(void *ctx, ble_runtime_info_t *info) { (void)ctx; memset(info, 0, sizeof(*info)); info->ble_tx_power_dbm = 6; return 0; }
static int refresh(void *ctx, uint32_t reason, bool force) { (void)ctx; (void)reason; (void)force; ++refreshes; return 0; }
static int wifi(void *ctx) { (void)ctx; ++wifi_applied; return 0; }
static int weather(void *ctx) { (void)ctx; ++weather_requests; return 0; }
static void parsed(const ble_frame_view_t *f, void *ctx) { (void)ctx; assert(f->type == 1 && f->sequence == 0x1234); ++frames; }
static int request(uint8_t type, const void *payload, uint16_t size, ble_command_reply_t *reply) {
    ble_frame_view_t f = {.version = 1, .type = type, .sequence = seq++, .payload_len = size, .payload = payload};
    return ble_commands_process_ex(&commands, &f, reply);
}
int main(void) {
    assert(ble_proto_crc16_ccitt((const uint8_t *)"123456789", 9) == 0x29b1);
    assert(ble_proto_crc32((const uint8_t *)"123456789", 9) == 0xcbf43926);
    const uint8_t golden[] = {0x45,0x50,1,1,0x34,0x12,0,0,0x2d,0x41};
    ble_frame_view_t f = {.version=1,.type=1,.sequence=0x1234};
    uint8_t encoded[32]; size_t length;
    assert(ble_proto_encode(&f, encoded, sizeof(encoded), &length) == 0);
    assert(length == sizeof(golden) && !memcmp(encoded, golden, length));
    ble_proto_stream_t parser; ble_proto_stream_init(&parser, parsed, NULL);
    uint8_t corrupt[10]; memcpy(corrupt, golden, 10); corrupt[9] ^= 1;
    ble_proto_stream_feed(&parser, corrupt, 10);
    for (size_t i=0;i<sizeof(golden);++i) ble_proto_stream_feed(&parser,golden+i,1);
    assert(frames == 1);
    ble_proto_stream_feed(&parser, golden, 5); ble_proto_stream_init(&parser, parsed, NULL);
    ble_proto_stream_feed(&parser, golden, 10); assert(frames == 2);
    nvs_store_config_defaults(&config);
    ble_commands_init(&commands, &config, persist, NULL, NULL, changed, NULL);
    const ble_commands_ops_t ops = {.get_runtime_info=runtime,.request_refresh=refresh,
        .apply_wifi_credentials=wifi,.request_weather_refresh=weather};
    ble_commands_set_ops(&commands, &ops);
    ble_command_reply_t reply; uint8_t value=3;
    assert(request(1,NULL,0,&reply)==0 && reply.data[0]==1 && reply.length>=45);
    assert(request(2,&value,1,&reply)==0 && config.rotation==3);
    persistence_failure=1; value=2;
    assert(request(2,&value,1,&reply)==2 && config.rotation==3); persistence_failure=0;
    value=1; assert(request(3,&value,1,&reply)==0 && config.home_mode==1);
    assert(request(4,NULL,0,&reply)==0);
    f=(ble_frame_view_t){.version=1,.type=5,.sequence=seq++};
    int before=refreshes;
    assert(ble_commands_process_ex(&commands,&f,&reply)==0 && config.current_photo_slot==1);
    assert(ble_commands_process_ex(&commands,&f,&reply)==0 && config.current_photo_slot==1 && refreshes==before+1);
    f.type=4; assert(ble_commands_process_ex(&commands,&f,&reply)==1);
    assert(request(6,"Asia/Shanghai",13,&reply)==0);
    assert(request(6,"bad//zone",9,&reply)==1);
    int32_t location[]={300000000,1200000000}; assert(request(7,location,8,&reply)==0);
    location[0]=1000000000; assert(request(7,location,8,&reply)==1);
    const uint8_t credentials[]={3,4,'a','p','1','p','a','s','s'};
    assert(request(8,credentials,sizeof(credentials),&reply)==0 && wifi_applied==1);
    uint8_t invalid[]={1,0,0}; assert(request(8,invalid,3,&reply)==1);
    assert(request(9,NULL,0,&reply)==0 && weather_requests==2);
    assert(request(14,"plan",4,&reply)==0 && !strcmp(config.today_plan,"plan"));
    uint8_t utf8[]={0xc0,0x80}; assert(request(14,utf8,2,&reply)==1);
    assert(request(14,NULL,0,&reply)==0);
    uint8_t begin[13]={0x7b,0,0,0,2};
    uint32_t total=PHOTO_STORE_BYTES, crc=1234;
    memcpy(begin+5,&total,4); memcpy(begin+9,&crc,4);
    assert(request(10,begin,13,&reply)==0 && reply.length==9 && reply.data[0]==2);
    uint8_t chunk[104]; uint32_t offset=100; memcpy(chunk,&offset,4); memset(chunk+4,0x55,100);
    assert(request(11,chunk,104,&reply)==0); assert(!memcmp(reply.data,"\0\0\0\0",4));
    offset=0; memcpy(chunk,&offset,4); assert(request(11,chunk,104,&reply)==0);
    assert(ble_upload_next_offset(&commands.upload)==200);
    assert(request(10,begin,13,&reply)==0 && ble_upload_next_offset(&commands.upload)==200);
    assert(request(11,chunk,104,&reply)==0 && ble_upload_next_offset(&commands.upload)==200);
    begin[0]=0x7c; assert(request(10,begin,13,&reply)==5); begin[0]=0x7b;
    assert(request(12,&crc,4,&reply)==3 && !commands.upload.active);
    assert(request(10,begin,13,&reply)==0);
    assert(request(13,NULL,0,&reply)==0 && !commands.upload.active);
    assert(request(10,begin,13,&reply)==0);
    // Cover every byte with arbitrary MTU-sized fragments, including the tail.
    uint8_t packet[502]; memset(packet+4,0x55,sizeof(packet)-4);
    for (offset=0;offset<total;) {
        uint16_t count=total-offset<498 ? total-offset : 498;
        memcpy(packet,&offset,4); assert(request(11,packet,count+4,&reply)==0); offset+=count;
    }
    assert(request(12,&crc,4,&reply)==0 && config.current_photo_slot==2 && reply.length==9);
    assert(request(0x7f,NULL,0,&reply)==4);
    ble_commands_ops_t ota_ops=ops;ota_ops.firmware_command=firmware_command;
    ble_commands_set_ops(&commands,&ota_ops);
    for(uint8_t type=BLE_CMD_OTA_BEGIN;type<=BLE_CMD_OTA_ABORT;++type) {
        assert(request(type,NULL,0,&reply)==BLE_COMMAND_OK && reply.length==1 && reply.data[0]==type);
    }
    f=(ble_frame_view_t){.version=1,.type=BLE_CMD_OTA_END,.sequence=seq++};
    assert(ble_commands_process_ex(&commands,&f,&reply)==0);
    int calls=ota_calls;
    assert(ble_commands_process_ex(&commands,&f,&reply)==0 && ota_calls==calls);
    puts("Firmware protocol: 14 commands, CRC, framing, replay, validation and resume passed");
    return 0;
}
