"""Compile the shared OTA component with a simulated SDK; requires Zig, Clang or GCC."""
import argparse
from pathlib import Path
import subprocess
import tempfile
parser=argparse.ArgumentParser()
parser.add_argument('--compiler',default='cc')
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
command=[args.compiler]+(['cc'] if Path(args.compiler).stem.lower()=='zig' else [])
with tempfile.TemporaryDirectory(prefix='epd-ota-test-') as directory:
    for header in ['esp_err.h','esp_app_desc.h','esp_app_format.h','esp_ota_ops.h','esp_rom_crc.h',
                   'esp_system.h','esp_timer.h','sdkconfig.h','freertos/FreeRTOS.h','freertos/task.h']:
        file=Path(directory,header);file.parent.mkdir(parents=True,exist_ok=True)
        file.write_text('#include "ota_stubs.h"\n')
    exe=Path(directory,'ota-test.exe')
    subprocess.run(command+['-DOTA_TEST_PROJECT="C3FN4_3.98_A0"','-std=c11','-Wall','-Wextra','-Werror','-I'+directory,
        '-I'+str(root/'tests/ota_stub'),'-I'+str(root/'components/firmware_update/include'),
        str(root/'tests/test_ota.c'),str(root/'components/firmware_update/firmware_update.c'),
        '-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
