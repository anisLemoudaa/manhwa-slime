#!/bin/bash
# Manhwa Slime setup: colors, package, name, icon, build workflow, banner
cd "$(dirname "$0")" || exit 1

# 1) الألوان: استرجاع الألوان الأصلية الزرقاء (تناسب الأيقونة)
F=app/src/main/java/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt
if [ -z "$SKIP_NET" ]; then
  if curl -fsSL "https://raw.githubusercontent.com/mihonapp/mihon/main/$F" -o /tmp/cs.kt && grep -q 0058CA /tmp/cs.kt; then
    cp /tmp/cs.kt "$F"; echo "[ok] colors restored to blue"
  else echo "[!!] could not restore colors"; fi
fi

# 2) اسم الحزمة والاسم
sed -i 's|"com.mangaslayer.plus"|"com.manhwaslime.app"|; s|"app.mihon"|"com.manhwaslime.app"|; s|applicationIdSuffix = ".debug"|applicationIdSuffix = ""|' app/build.gradle.kts
grep -n 'applicationId = ' app/build.gradle.kts
find i18n -name strings.xml -exec sed -i 's|\(name="app_name"[^>]*>\)[^<]*<|\1Manhwa Slime<|' {} +
echo "[ok] name + package"

# 3) الأيقونة (أول صورة PNG شبه مربعة في المجلد) والبانر (أي صورة غير مربعة)
python3 -m pip install -q pillow 2>/dev/null
python3 - <<'EOF'
import os,glob
from PIL import Image,ImageDraw
pngs=sorted(glob.glob('*.png'),key=os.path.getmtime,reverse=True)
icon=banner=None
for p in pngs:
    w,h=Image.open(p).size
    if abs(w-h)<0.1*max(w,h): icon=icon or p
    else: banner=banner or p
if banner:
    os.makedirs('assets',exist_ok=True); os.replace(banner,'assets/banner.png'); print('[ok] banner ->',banner)
if not icon: print('[!!] no square icon png found'); raise SystemExit
src=Image.open(icon).convert('RGB'); w,h=src.size; px=src.load()
a=Image.new('L',(w,h)); ap=a.load()
for y in range(h):
    for x in range(w):
        r,g,b=px[x,y]; ap[x,y]=int(255*min(1,max(0,(b-r-40)/80)))
fg=src.convert('RGBA'); fg.putalpha(a); fg=fg.crop(a.getbbox())
BG=(0x0F,0x0F,0x13,255)
def place(c,f):
    s=c*f/max(fg.size); t=fg.resize((int(fg.width*s),int(fg.height*s)),Image.LANCZOS)
    im=Image.new('RGBA',(c,c),(0,0,0,0)); im.paste(t,((c-t.width)//2,(c-t.height)//2),t); return im
for f in glob.glob('app/src/*/res/mipmap-*/*'):
    if os.path.splitext(os.path.basename(f))[0] in ('ic_launcher','ic_launcher_round'): os.remove(f)
R='app/src/main/res/'
for d in ('drawable-nodpi','values','mipmap-anydpi-v26'): os.makedirs(R+d,exist_ok=True)
place(432,0.62).save(R+'drawable-nodpi/ic_msl_fg.png')
open(R+'values/ic_msl_colors.xml','w').write('<resources><color name="ic_msl_bg">#0F0F13</color></resources>')
xml='<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/ic_msl_bg"/><foreground android:drawable="@drawable/ic_msl_fg"/><monochrome android:drawable="@drawable/ic_msl_fg"/></adaptive-icon>'
for n in ('ic_launcher','ic_launcher_round'): open(R+'mipmap-anydpi-v26/'+n+'.xml','w').write(xml)
for d,s in {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}.items():
    os.makedirs(R+'mipmap-'+d,exist_ok=True)
    base=Image.new('RGBA',(s,s),BG); base.alpha_composite(place(s,0.8))
    for n,shape in (('ic_launcher','rr'),('ic_launcher_round','c')):
        m=Image.new('L',(s,s),0); dr=ImageDraw.Draw(m)
        if shape=='rr': dr.rounded_rectangle([0,0,s-1,s-1],radius=int(s*.22),fill=255)
        else: dr.ellipse([0,0,s-1,s-1],fill=255)
        o=base.copy(); o.putalpha(m); o.save(R+'mipmap-%s/%s.png'%(d,n))
os.remove(icon); print('[ok] icons from',icon)
EOF
[ -f assets/banner.png ] && ! grep -q 'assets/banner.png' README.md && sed -i '1i ![Manhwa Slime](assets/banner.png)\n' README.md

# 4) ملف البناء
mkdir -p .github/workflows
cat > .github/workflows/msp.yml <<'EOF'
name: Build Manhwa Slime
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - run: chmod +x gradlew && ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with: { name: ManhwaSlime, path: app/build/outputs/apk/debug/*.apk }
EOF
echo "[ok] workflow"

# 5) رفع التعديلات
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Manhwa Slime: branding, icon, package" && git push && echo "[ok] pushed"
fi
