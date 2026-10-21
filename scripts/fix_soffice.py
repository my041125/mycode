"""给技能里的 soffice.py 打 Windows 兼容补丁。

原文件是 Linux/macOS 思路：如果 AF_UNIX socket 建不出来，就认为需要
LD_PRELOAD 的 shim。但 Windows 上压根没有 socket.AF_UNIX，
于是它误判成「需要 shim」，接着去调 gcc 编译 .so，必然失败。

补丁两处：
1. _needs_shim() 在 win32 上直接返回 False（Windows 不需要、也不可能用 shim）
2. except 增加 AttributeError 兜底（某些平台上 AF_UNIX 属性不存在）
"""
import re
import shutil
from pathlib import Path

SKILLS = Path.home() / ".claude" / "skills"
TARGETS = [SKILLS / "docx" / "scripts" / "office" / "soffice.py",
           SKILLS / "pptx" / "scripts" / "office" / "soffice.py"]

GUARD = "    if sys.platform == \"win32\":   # patched: Windows 没有 AF_UNIX，shim 是 Linux 专用\n        return False\n"

for path in TARGETS:
    if not path.is_file():
        print(f"跳过（不存在）: {path}")
        continue

    src = path.read_text(encoding="utf-8")

    # 已经打过补丁就不再重复插入
    if "patched: Windows 没有 AF_UNIX" in src:
        print(f"已是最新: {path.name}")
        continue

    # 1. 在 _needs_shim 开头插入平台判断
    src = src.replace(
        "def _needs_shim() -> bool:\n    try:\n",
        "def _needs_shim() -> bool:\n" + GUARD + "    try:\n",
        1,
    )

    # 2. 异常捕获加上 AttributeError
    src = src.replace("    except OSError:\n", "    except (OSError, AttributeError):\n", 1)

    # 3. 确保 sys 已导入（文件底部用了 sys.argv，正常都有，这里做个保险）
    if not re.search(r"^import sys$", src, re.M):
        src = src.replace("import subprocess\n", "import subprocess\nimport sys\n", 1)

    path.write_text(src, encoding="utf-8")
    print(f"已修补: {path}")
