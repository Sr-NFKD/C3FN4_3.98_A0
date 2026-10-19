/* Host checks for the actual shared OTA component, with a simulated OTA slot. */
#include "ota_stubs.h"
#include "firmware_update.h"
#include <stdio.h>
#include <string.h>
static uint8_t image[1024], slot[0x160000];
static const esp_partition_t partition = {.size=sizeof(slot)};
#ifndef OTA_TEST_PROJECT
#define OTA_TEST_PROJECT "C3FN4_3.98_A0_BLE"
#endif
static const esp_app_desc_t running = {.magic_word=ESP_APP_DESC_MAGIC_WORD,.project_name=OTA_TEST_PROJECT};
static esp_partition_t photos = {.address=0x2d0000,.size=0xd0000};
static esp_partition_t fonts = {.address=0x3a0000,.size=0x60000};
static int missing_partition, bad_font;
const esp_partition_t *esp_partition_find_first(int type,int subtype,const char *label) {
    assert(type==ESP_PARTITION_TYPE_DATA);
    if(missing_partition)return NULL;
    if(!strcmp(label,"photos")){assert(subtype==0x40);return &photos;}
    assert(!strcmp(label,"fonts") && subtype==0x41);return &fonts;
}
static size_t written;
static int64_t now;
static int boot_changes, aborts, confirms, restart_tasks;
static int fail_begin, fail_write, fail_end, fail_boot;
static esp_ota_img_states_t boot_state = ESP_OTA_IMG_PENDING_VERIFY;
const esp_app_desc_t *esp_app_get_description(void) { return &running; }
const esp_partition_t *esp_ota_get_next_update_partition(const void *unused) { (void)unused; return &partition; }
const esp_partition_t *esp_ota_get_running_partition(void) { return &partition; }
esp_err_t esp_ota_get_state_partition(const esp_partition_t *p,esp_ota_img_states_t *s) { (void)p; *s=boot_state; return ESP_OK; }
esp_err_t esp_ota_mark_app_valid_cancel_rollback(void) { ++confirms; boot_state=ESP_OTA_IMG_VALID; return ESP_OK; }
esp_err_t esp_ota_begin(const esp_partition_t *p,size_t size,esp_ota_handle_t *h) {
    (void)p; (void)size; if(fail_begin)return ESP_FAIL; *h=1; written=0; memset(slot,255,sizeof(slot));return ESP_OK;
}
esp_err_t esp_ota_abort(esp_ota_handle_t h) { (void)h; ++aborts; return ESP_OK; }
esp_err_t esp_ota_write(esp_ota_handle_t h,const void *data,size_t size) {
    (void)h; if(fail_write)return ESP_FAIL;
    assert(written+size<=sizeof(slot));memcpy(slot+written,data,size);written+=size;return ESP_OK;
}
esp_err_t esp_partition_read(const esp_partition_t *p,size_t offset,void *data,size_t size) {
    if(p==&fonts){assert(offset==0 && size==4);memcpy(data,bad_font?"BAD!":"EPF1",4);return ESP_OK;}
    (void)p; assert(offset+size<=written);memcpy(data,slot+offset,size);return ESP_OK;
}
esp_err_t esp_ota_end(esp_ota_handle_t h) { (void)h; return fail_end?ESP_FAIL:ESP_OK; }
esp_err_t esp_ota_set_boot_partition(const esp_partition_t *p) { (void)p; if(fail_boot)return ESP_FAIL; ++boot_changes;return ESP_OK; }
int64_t esp_timer_get_time(void) { return now; }
uint32_t esp_rom_crc32_le(uint32_t crc,const uint8_t *data,size_t size) {
    crc=~crc;for(size_t i=0;i<size;++i){crc^=data[i];for(int bit=0;bit<8;++bit)crc=(crc>>1)^((crc&1)?0xedb88320u:0);}return ~crc;
}
int xTaskCreate(void (*task)(void *),const char *name,unsigned stack,void *arg,unsigned priority,void *result) {
    (void)task;(void)name;(void)stack;(void)arg;(void)priority;(void)result;++restart_tasks;return pdPASS;
}
void vTaskDelay(unsigned ticks) { (void)ticks; }
void vTaskDelete(void *task) { (void)task; }
void esp_restart(void) {}
static uint32_t valid_image(void) {
    for(size_t i=0;i<sizeof(image);++i)image[i]=(uint8_t)i;
    esp_image_header_t h={.magic=ESP_IMAGE_HEADER_MAGIC,.chip_id=CONFIG_IDF_FIRMWARE_CHIP_ID};
    memcpy(image,&h,sizeof(h));memcpy(image+32,&running,sizeof(running));
    return esp_rom_crc32_le(0,image,sizeof(image));
}
static void upload(uint32_t crc) {
    assert(firmware_update_begin(sizeof(image),crc)==ESP_OK);
    assert(firmware_update_write(0,image,20)==ESP_OK);
    assert(firmware_update_write(0,image,20)==ESP_OK); // retry before header is flushed
    assert(written==0);
    assert(firmware_update_write(20,image+20,sizeof(image)-20)==ESP_OK);
    assert(firmware_update_received()==sizeof(image));
    assert(firmware_update_write(20,image+20,400)==ESP_OK); // retry after flush
    assert(written==sizeof(image) && !memcmp(image,slot,sizeof(image)));
}
int main(void) {
    assert(esp_rom_crc32_le(0,(const uint8_t *)"123456789",9)==0xcbf43926u);
    firmware_update_confirm_boot();firmware_update_confirm_boot();assert(confirms==1);
    assert(firmware_update_capacity()==sizeof(slot));
    assert(firmware_update_begin(287,0)==ESP_ERR_INVALID_SIZE);
    assert(firmware_update_begin(sizeof(slot)+1,0)==ESP_ERR_INVALID_SIZE);
    assert(firmware_update_schedule_restart()==ESP_ERR_INVALID_STATE);
    const char *other = !strcmp(OTA_TEST_PROJECT,"C3FN4_3.98_A0_BLE") ? "C3FN4_3.98_A0" : "C3FN4_3.98_A0_BLE";
    valid_image();memset(image+80,0,32);strcpy((char *)image+80,other);
    missing_partition=1;
    assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_STATE);
    missing_partition=0;fonts.address++;
    assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_STATE);
    fonts.address--;
    if(!strcmp(other,"C3FN4_3.98_A0_BLE")){
        bad_font=1;assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
        assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_STATE);bad_font=0;
    }
    upload(esp_rom_crc32_le(0,image,sizeof(image)));firmware_update_abort();
    uint32_t crc=valid_image();
    assert(firmware_update_begin(sizeof(image),crc)==ESP_OK);
    assert(firmware_update_write(1,image,10)==ESP_ERR_INVALID_ARG);
    assert(firmware_update_write(0,image,sizeof(image)+1)==ESP_ERR_INVALID_SIZE);
    assert(firmware_update_write(0,image,20)==ESP_OK);
    uint8_t wrong[20];memset(wrong,0,sizeof(wrong));assert(firmware_update_write(0,wrong,20)==ESP_ERR_INVALID_ARG);
    assert(firmware_update_commit()==ESP_ERR_INVALID_STATE);assert(boot_changes==0);
    firmware_update_abort();assert(!firmware_update_active());
    // A new session replaces an interrupted one, but cannot alter the boot choice.
    assert(firmware_update_begin(sizeof(image),crc)==ESP_OK);
    assert(firmware_update_begin(sizeof(image),crc)==ESP_OK);assert(aborts>=2);
    now+=60000001;assert(firmware_update_write(0,image,20)==ESP_ERR_TIMEOUT);
    assert(!firmware_update_active() && boot_changes==0);
    image[12]=9;
    assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_ARG);
    assert(!firmware_update_active());
    crc=valid_image();image[80]='X';
    assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_ARG);
    crc=valid_image();memset(image+80,'X',32);
    assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_ARG);
    crc=valid_image();image[32]=0;
    assert(firmware_update_begin(sizeof(image),0)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_ERR_INVALID_ARG);
    crc=valid_image();upload(crc^1);
    assert(firmware_update_commit()==ESP_ERR_INVALID_CRC && boot_changes==0);
    fail_begin=1;assert(firmware_update_begin(sizeof(image),crc)==ESP_FAIL);fail_begin=0;
    fail_write=1;assert(firmware_update_begin(sizeof(image),crc)==ESP_OK);
    assert(firmware_update_write(0,image,sizeof(image))==ESP_FAIL && !firmware_update_active());fail_write=0;
    upload(crc);fail_end=1;assert(firmware_update_commit()==ESP_FAIL && boot_changes==0);fail_end=0;
    upload(crc);fail_boot=1;assert(firmware_update_commit()==ESP_FAIL && boot_changes==0);fail_boot=0;
    valid_image();memset(image+80,0,32);strcpy((char *)image+80,other);
    crc=esp_rom_crc32_le(0,image,sizeof(image));
    upload(crc);assert(firmware_update_commit()==ESP_OK && boot_changes==1 && !firmware_update_active());
    assert(firmware_update_begin(sizeof(image),crc)==ESP_ERR_INVALID_STATE);
    assert(firmware_update_schedule_restart()==ESP_OK);
    assert(firmware_update_schedule_restart()==ESP_OK && restart_tasks==1);
    puts("OTA: wrong firmware, size/CRC errors, timeout, retries, flash failures, boot confirmation and commit passed");
    return 0;
}
