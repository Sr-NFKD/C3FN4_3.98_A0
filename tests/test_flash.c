/* Exercise the actual ESP_PLATFORM storage path using NOR-flash semantics. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "esp_partition.h"
#include "storage/photo_store.h"
#include "ble/ble_protocol.h"
static uint8_t flash[PHOTO_STORE_PARTITION_SIZE], frame[PHOTO_STORE_BYTES];
static const esp_partition_t partition = {.size=sizeof(flash)};
static int fail_commit;
const esp_partition_t *esp_partition_find_first(int type, int subtype, const char *name) {
    (void)type; (void)subtype; return !strcmp(name,"photos") ? &partition : NULL;
}
esp_err_t esp_partition_read(const esp_partition_t *p,size_t offset,void *buffer,size_t size) {
    if (offset>p->size || size>p->size-offset) return ESP_FAIL;
    memcpy(buffer,flash+offset,size); return ESP_OK;
}
esp_err_t esp_partition_write(const esp_partition_t *p,size_t offset,const void *buffer,size_t size) {
    if (offset>p->size || size>p->size-offset || (fail_commit && offset%PHOTO_STORE_SLOT_STRIDE==PHOTO_STORE_BYTES)) return ESP_FAIL;
    const uint8_t *bytes=buffer;
    for (size_t i=0;i<size;++i) {
        if ((flash[offset+i] & bytes[i])!=bytes[i]) return ESP_FAIL;
        flash[offset+i] &= bytes[i];
    }
    return ESP_OK;
}
esp_err_t esp_partition_erase_range(const esp_partition_t *p,size_t offset,size_t size) {
    if (offset%4096 || size%4096 || offset>p->size || size>p->size-offset) return ESP_FAIL;
    memset(flash+offset,255,size); return ESP_OK;
}
static photo_store_begin_t begin(uint32_t id,uint8_t color) {
    memset(frame,color,sizeof(frame));
    photo_store_begin_t b={.photo_id=id,.slot=2,.total_len=sizeof(frame),.crc32=ble_proto_crc32(frame,sizeof(frame))};
    assert(photo_store_begin(&b)==ESP_OK);
    return b;
}
static void upload(void) {
    size_t middle=sizeof(frame)/2;
    assert(photo_store_write(2,middle,frame+middle,sizeof(frame)-middle)==ESP_OK);
    assert(photo_store_write(2,0,frame,middle)==ESP_OK);
    assert(photo_store_write(2,0,frame,100)==ESP_OK); // identical retransmission
}
static void saved(uint32_t id,uint8_t color) {
    photo_store_info_t info; size_t size; uint8_t sample[128];
    assert(photo_store_get(2,&info,NULL,0,&size)==ESP_ERR_INVALID_SIZE);
    assert(info.valid && info.photo_id==id && size==PHOTO_STORE_BYTES);
    assert(photo_store_read(2,2000,sample,sizeof(sample))==ESP_OK);
    for(size_t i=0;i<sizeof(sample);++i) assert(sample[i]==color);
}
int main(void) {
    memset(flash,255,sizeof(flash)); assert(photo_store_init()==ESP_OK);
    photo_store_begin_t b=begin(1,0x55); upload(); assert(photo_store_end(&b,b.crc32)==ESP_OK); saved(1,0x55);
    b=begin(2,0xaa); assert(photo_store_write(2,0,frame,1000)==ESP_OK); saved(1,0x55);
    assert(photo_store_abort(2)==ESP_OK); assert(photo_store_init()==ESP_OK); saved(1,0x55);
    b=begin(3,0xaa); upload(); fail_commit=1;
    assert(photo_store_end(&b,b.crc32)!=ESP_OK); fail_commit=0;
    assert(photo_store_init()==ESP_OK); saved(1,0x55);
    b=begin(4,0xaa); upload();
    // Simulate a damaged data byte before final read-back CRC verification.
    size_t other=2*PHOTO_STORE_BANK_COUNT*PHOTO_STORE_SLOT_STRIDE;
    flash[other] &= 0x80;
    assert(photo_store_end(&b,b.crc32)==ESP_ERR_INVALID_CRC);
    assert(photo_store_init()==ESP_OK); saved(1,0x55);
    b=begin(5,0x1b); upload(); assert(photo_store_end(&b,b.crc32)==ESP_OK);
    assert(photo_store_init()==ESP_OK); saved(5,0x1b);
    puts("Flash storage: out-of-order/duplicate data, CRC, abort, failed commit and reboot passed");
    return 0;
}
