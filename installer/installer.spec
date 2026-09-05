# -*- mode: python ; coding: utf-8 -*-
# RX100M3-LUT 安装器打包配置：
#   pyinstaller installer.spec
# 产出：dist/RX100M3-LUT-Installer.exe（单文件，含 pmca 库 + APK + LUT）

import os
import sys

block_cipher = None
PMCA_SRC = os.environ.get('PMCA_SRC', r'D:\pmca-tool\Sony-PMCA-RE-master')
import site as _s
SITE = [p for p in _s.getsitepackages() if 'site-packages' in p][0]

a = Analysis(
    ['installer.py'],
    pathex=[PMCA_SRC],
    binaries=[],
    datas=[
        ('assets/CustomLut.apk', 'assets'),
        ('assets/luts', 'assets/luts'),
        (os.path.join(PMCA_SRC, 'certs'), 'certs'),
        # androguard 解析 APK 时需要资源文件
        (os.path.join(SITE, 'androguard', 'core', 'resources', 'public.xml'),
         'androguard/core/resources'),
        (os.path.join(SITE, 'androguard', 'core', 'resources', 'public.json'),
         'androguard/core/resources'),
    ],
    hiddenimports=[
        'pmca', 'pmca.commands', 'pmca.commands.usb',
        'pmca.usb', 'pmca.usb.sony', 'pmca.usb.constants', 'pmca.usb.crypto',
        'pmca.usb.driver', 'pmca.usb.driver.generic', 'pmca.usb.driver.generic.libusb',
        'pmca.usb.driver.windows', 'pmca.usb.driver.windows.msc',
        'pmca.usb.driver.windows.wpd', 'pmca.usb.driver.windows.setupapi',
        'pmca.usb.driver.windows.driverless',
        'pmca.installer', 'pmca.marketserver', 'pmca.marketserver.server',
        'pmca.spk', 'pmca.xpd', 'pmca.util', 'pmca.apk',
        'androguard', 'androguard.core', 'androguard.core.axml',
        'asn1crypto', 'asn1crypto.cms',
        'tlslite', 'tlslite.api', 'tlslite.tlsconnection', 'tlslite.x509',
        'tlslite.x509certchain', 'tlslite.utils', 'tlslite.utils.pem',
        'tlslite.utils.cryptomath', 'tlslite.utils.codec',
        'tlslite.handshakesettings', 'tlslite.session', 'tlslite.constants',
        'comtypes', 'comtypes.client', 'win32com',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['numpy', 'PIL', 'matplotlib', 'pandas', 'scipy'],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='RX100M3-LUT-Installer',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
