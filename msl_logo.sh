#!/bin/bash
# استبدال شعار Mihon (ic_mihon) بشعار السلايم في كل المواضع
cd "$(dirname "$0")" || exit 1
SRC=app/src/main/res/drawable-nodpi/ic_msl_fg.png
[ -f "$SRC" ] || { echo "[!!] $SRC not found (run msl_setup.sh first)"; exit 1; }
python3 -m pip install -q pillow 2>/dev/null
python3 - <<'EOF'
from PIL import Image
fg=Image.open('app/src/main/res/drawable-nodpi/ic_msl_fg.png').convert('RGBA')
fg=fg.crop(fg.getchannel('A').getbbox())
c=512; s=c*0.86/max(fg.size)
t=fg.resize((int(fg.width*s),int(fg.height*s)),Image.LANCZOS)
im=Image.new('RGBA',(c,c),(0,0,0,0)); im.paste(t,((c-t.width)//2,(c-t.height)//2),t)
im.save('app/src/main/res/drawable-nodpi/ic_mihon.png'); print('[ok] ic_mihon.png')
EOF
# حذف الرسم المتجهي القديم حتى لا يتعارض مع الصورة الجديدة
find . -name 'ic_mihon.xml' -not -path '*/build/*' -print -delete
ls app/src/main/res/drawable-nodpi/ic_mihon.png && echo "[ok] logo replaced"
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Replace Mihon logo with slime" && git push && echo "[ok] pushed"
fi
