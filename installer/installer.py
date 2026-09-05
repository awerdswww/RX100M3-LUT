#!/usr/bin/env python3
"""RX100M3-LUT 安装器：检测相机 → 装 App → 上传 LUT。

打包：pyinstaller --noconsole --onefile --name RX100M3-LUT-Installer installer.py
资源（assets/ 下）：CustomLut.apk、luts/*.CUB
"""
import glob
import io
import os
import shutil
import sys
import threading
import tkinter as tk
from tkinter import messagebox, ttk

# pmca 库（PyInstaller 打包时随带；开发时从 PMCA_SRC 读）
if getattr(sys, 'frozen', False):
    BASE = sys._MEIPASS
else:
    BASE = os.path.dirname(os.path.abspath(__file__))
    src = os.environ.get('PMCA_SRC', r'D:\pmca-tool\Sony-PMCA-RE-master')
    if os.path.isdir(src):
        sys.path.insert(0, src)

from pmca.commands.usb import getDevice, installApp  # noqa: E402

APK = os.path.join(BASE, 'assets', 'CustomLut.apk')
LUTS = os.path.join(BASE, 'assets', 'luts')


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('RX100M3-LUT 安装器')
        self.resizable(False, False)
        self.status = tk.StringVar(value='相机用 USB 线连接，USB 模式设为 MTP（菜单→设置→USB连接）')
        tk.Label(self, textvariable=self.status, wraplength=380,
                 justify='left').pack(padx=12, pady=(12, 6), fill='x')
        row = tk.Frame(self)
        row.pack(pady=6)
        self.btn_install = tk.Button(row, text='1. 安装 App 到相机',
                                     command=lambda: self.run(self.do_install), width=20)
        self.btn_install.pack(side='left', padx=4)
        self.btn_luts = tk.Button(row, text='2. 上传 LUT 到 SD 卡',
                                  command=lambda: self.run(self.do_luts), width=20)
        self.btn_luts.pack(side='left', padx=4)
        tk.Label(self, text='上传 LUT：SD 卡插读卡器，或相机 USB 模式切「海量存储」',
                 fg='#666').pack(padx=12, pady=(0, 10), fill='x')

    def run(self, fn):
        self.btn_install['state'] = 'disabled'
        self.btn_luts['state'] = 'disabled'
        threading.Thread(target=self._wrap, args=(fn,), daemon=True).start()

    def _wrap(self, fn):
        try:
            fn()
        except Exception as e:
            self.status.set('出错：%s' % e)
        finally:
            self.btn_install['state'] = 'normal'
            self.btn_luts['state'] = 'normal'

    def say(self, s):
        self.status.set(s)
        self.update_idletasks()

    def do_install(self):
        self.say('正在检测相机…')
        dev = getDevice('native')
        if dev is None:
            self.say('没找到相机。检查：USB 线连好、相机开机、USB 模式是 MTP。')
            return
        self.say('相机已识别，正在安装（相机屏幕保持亮着）…')
        installApp(dev, apkFile=io.BytesIO(open(APK, 'rb').read()))
        self.say('安装完成！相机的「应用程序列表」里找 CUSTOM LUT。'
                 '若相机提示证书冲突，先在「应用程序管理」卸载旧版再装。')

    def do_luts(self):
        target = self.find_sd()
        if target is None:
            self.say('没找到 SD 卡。插读卡器，或相机 USB 切「海量存储」模式。')
            return
        lut_dir = os.path.join(target, 'LUTS')
        os.makedirs(lut_dir, exist_ok=True)
        n = 0
        for f in glob.glob(os.path.join(LUTS, '*.CUB')):
            shutil.copy2(f, os.path.join(lut_dir, os.path.basename(f)))
            n += 1
            self.say('已上传 %d 个 LUT 到 %s …' % (n, lut_dir))
        self.say('完成：%d 个 LUT 已写入 %s。相机上启动 CUSTOM LUT 即可用。'
                 % (n, lut_dir))

    @staticmethod
    def find_sd():
        """找插着的 SD 卡：有 DCIM 目录的可移动盘即视为相机卡。"""
        import ctypes
        drives = []
        bitmask = ctypes.windll.kernel32.GetLogicalDrives()
        for i in range(26):
            if bitmask & (1 << i):
                drives.append('%c:\\' % (ord('A') + i))
        for d in drives:
            if ctypes.windll.kernel32.GetDriveTypeW(d) == 2:  # DRIVE_REMOVABLE
                if os.path.isdir(os.path.join(d, 'DCIM')):
                    return d
        return None


if __name__ == '__main__':
    App().mainloop()
