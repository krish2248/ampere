"""Generates the small XML resources (icons, widget, backup rules, colours). Run from the repo root."""
import math, os, re

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
X = '<?xml version="1.0" encoding="utf-8"?>\n'
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def w(p, t):
    p = os.path.join(RES, p)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8", newline="\n").write(X + t)


w("xml/data_extraction_rules.xml", """<data-extraction-rules>
    <cloud-backup>
        <include domain="database" path="." />
        <include domain="file" path="datastore" />
    </cloud-backup>
    <!-- A new phone has a new battery: history from the old one must not follow. -->
    <device-transfer>
        <exclude domain="database" path="." />
        <exclude domain="file" path="datastore" />
    </device-transfer>
</data-extraction-rules>
""")
w("xml/backup_rules.xml", """<full-backup-content>
    <include domain="database" path="." />
    <include domain="file" path="datastore" />
</full-backup-content>
""")
w("xml/file_paths.xml", '<paths>\n    <cache-path name="share" path="share/" />\n</paths>\n')
w("xml/widget_info.xml", f"""<appwidget-provider {NS}
    android:description="@string/widget_desc"
    android:initialLayout="@layout/widget"
    android:previewLayout="@layout/widget"
    android:minWidth="110dp"
    android:minHeight="50dp"
    android:targetCellWidth="2"
    android:targetCellHeight="1"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen" />
""")
w("layout/widget.xml", f"""<LinearLayout {NS}
    android:id="@+id/w_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:gravity="center_vertical"
    android:orientation="vertical"
    android:paddingHorizontal="16dp"
    android:paddingVertical="10dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="bottom"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/w_level"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:fontFamily="sans-serif-medium"
            android:maxLines="1"
            android:text="@string/app_name"
            android:textColor="@color/w_text"
            android:textSize="22sp" />

        <TextView
            android:id="@+id/w_power"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:maxLines="1"
            android:textColor="@color/w_text"
            android:textSize="14sp" />
    </LinearLayout>

    <ProgressBar
        android:id="@+id/w_bar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="4dp"
        android:layout_marginVertical="6dp"
        android:max="100"
        android:progressDrawable="@drawable/widget_bar" />

    <TextView
        android:id="@+id/w_detail"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:maxLines="1"
        android:textColor="@color/w_dim"
        android:textSize="12sp" />
</LinearLayout>
""")
w("drawable/widget_bg.xml", f'<shape {NS}>\n    <solid android:color="@color/w_bg" />\n    <corners android:radius="22dp" />\n</shape>\n')
w("drawable/widget_bar.xml", f"""<layer-list {NS}>
    <item android:id="@android:id/background">
        <shape><solid android:color="@color/w_track" /><corners android:radius="2dp" /></shape>
    </item>
    <item android:id="@android:id/progress">
        <clip><shape><solid android:color="@color/w_accent" /><corners android:radius="2dp" /></shape></clip>
    </item>
</layer-list>
""")
w("drawable/ic_bolt.xml", f"""<vector {NS}
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M13.2,2.2 L5.2,13.4 C4.9,13.8 5.2,14.4 5.7,14.4 L10.6,14.4 L9.4,21.2 C9.3,21.9 10.2,22.3 10.6,21.7 L18.8,10.3 C19.1,9.9 18.8,9.3 18.3,9.3 L13.5,9.3 L14.4,2.7 C14.5,2 13.6,1.7 13.2,2.2 Z" />
</vector>
""")

# The icon is the app's own dial: three quarters of the ticks lit, around a bolt.
BOLT = "M56.2,40.5 L45.6,55.4 C45.2,56 45.6,56.8 46.3,56.8 L52.6,56.8 L51.2,66.3 C51.1,67.3 52.3,67.8 52.9,67 L63,52.1 C63.4,51.5 63,50.7 62.3,50.7 L56.4,50.7 L57.9,41.3 C58,40.3 56.8,39.7 56.2,40.5 Z"


def icon(lit, dim, bolt):
    paths = []
    for i in range(40):
        a = math.radians(-90 + 360 * i / 40)
        (x1, y1), (x2, y2) = [(54 + r * math.cos(a), 54 + r * math.sin(a)) for r in (21.5, 27.5)]
        paths.append(f'    <path android:strokeColor="{lit if i < 30 else dim}" android:strokeWidth="1.9" android:strokeLineCap="round" '
                     f'android:pathData="M{x1:.2f},{y1:.2f} L{x2:.2f},{y2:.2f}" />')
    return (f'<vector {NS}\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            + "\n".join(paths) + f'\n    <path android:fillColor="{bolt}" android:pathData="{BOLT}" />\n</vector>\n')


w("drawable/ic_launcher_foreground.xml", icon("#5FE0A3", "#33383F", "#F4F5F7"))
w("drawable/ic_launcher_mono.xml", icon("#FFFFFF", "#66FFFFFF", "#FFFFFF"))
for n in ("ic_launcher", "ic_launcher_round"):
    w(f"mipmap-anydpi-v26/{n}.xml", f"""<adaptive-icon {NS}>
    <background android:drawable="@color/ampere_bg" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
""")


def colors(**k):
    return "<resources>\n" + "".join(f'    <color name="{n}">{v}</color>\n' for n, v in k.items()) + "</resources>\n"


w("values/colors.xml", colors(ampere_bg="#FF0B0C0E", window_bg="#FFF5F6F8", w_bg="#FFFFFFFF", w_text="#FF14161A",
                              w_dim="#FF626973", w_track="#FFE3E6EB", w_accent="#FF12875A"))
w("values-night/colors.xml", colors(window_bg="#FF0B0C0E", w_bg="#FF15181C", w_text="#FFF2F3F5", w_dim="#FF979DA8",
                                    w_track="#FF2A2F36", w_accent="#FF5FE0A3"))
w("values/strings.xml", """<resources>
    <string name="app_name">Ampere</string>
    <string name="widget_desc">Battery level, live watts and time to full</string>
</resources>
""")
w("values/themes.xml", """<resources>
    <style name="Theme.Ampere" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/window_bg</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>

    <style name="Theme.Ampere.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/ampere_bg</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="postSplashScreenTheme">@style/Theme.Ampere</item>
    </style>
</resources>
""")
