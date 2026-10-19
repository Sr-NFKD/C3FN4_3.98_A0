"""Run real firmware C checks on a host compiler (Zig, GCC or Clang)."""
import argparse
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--compiler', default='cc', help='C compiler executable; zig.exe is also supported')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
source = root / 'components/ble_epaper'
compiler = [args.compiler]
if pathlib.Path(args.compiler).stem.lower() == 'zig':
    compiler.append('cc')
with tempfile.TemporaryDirectory(prefix='epd-protocol-check-') as directory:
    # Compile the actual startup paths with a stub panel that can fail.
    entry = (root / 'main/app_main.c').read_text(encoding='utf-8-sig')
    worker = (source / 'ble_epaper.c').read_text(encoding='utf-8-sig')
    worker = worker[worker.index('static void refresh_task(void *arg)\n{'):]
    worker = worker[:worker.index('    refresh_queue_item_t item;')] + '}\n'
    pathlib.Path(directory, 'app_entry.inc').write_text(entry[entry.index('void app_main(void)'):], encoding='utf-8')
    pathlib.Path(directory, 'startup_clear.inc').write_text(worker, encoding='utf-8')
    for header in ['esp_err.h', 'esp_app_desc.h', 'esp_app_format.h', 'esp_ota_ops.h',
                   'esp_rom_crc.h', 'esp_system.h', 'esp_timer.h', 'sdkconfig.h',
                   'freertos/FreeRTOS.h', 'freertos/task.h']:
        target = pathlib.Path(directory, header)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('#include "ota_stubs.h"\n', encoding='utf-8')
    for name, files, flags in [
        ('startup', [], ['-I' + directory]),
        ('ota', ['../firmware_update/firmware_update.c'],
         ['-I' + directory, '-I' + str(root / 'tests/ota_stub'),
          '-I' + str(root / 'components/firmware_update/include')]),
        ('protocol', ['ble/ble_protocol.c', 'ble/ble_commands.c', 'ble/ble_upload.c',
                      'storage/photo_store.c', 'storage/nvs_store.c'], []),
        ('flash', ['ble/ble_protocol.c', 'storage/photo_store.c'],
         ['-DESP_PLATFORM=1', '-I' + str(root / 'tests/flash_stub')]),
    ]:
        exe = pathlib.Path(directory) / (name + '.exe')
        command = compiler + ['-std=c11', '-D_POSIX_C_SOURCE=200809L', '-Wall', '-Wextra',
                              '-Werror', '-I' + str(source)] + flags
        command += [str(root / 'tests' / ('test_' + name + '.c'))]
        command += [str(source / file) for file in files] + ['-o', str(exe)]
        subprocess.run(command, check=True, cwd=root)
        subprocess.run([str(exe)], check=True, cwd=root)
