package com.tinkerlab.maameowpatch;

import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部 MaaCore 资源：仅当 Intent 显式携带 {@link #EXTRA_RESOURCE_PATH} 时生效。
 * Meow 内部/UI 触发的资源加载一律走内置路径，不受本配置影响。
 *
 * <p>AsstLoadResource(parent) 要求 parent 下存在 {@code resource/} 子目录。
 */
public final class ResourcePathConfig {

    public static final String MODE_APPEND = "append";
    public static final String MODE_REPLACE = "replace";

    /** 必填才启用外部资源：AsstLoadResource 的 parent 路径 */
    public static final String EXTRA_RESOURCE_PATH = "extra_resource_path";
    /** append（默认，叠加在内置之后）| replace（本次 reload 时替换主资源） */
    public static final String EXTRA_RESOURCE_MODE = "extra_resource_mode";
    /** 可选：本地补丁 parent，最后加载、优先级最高 */
    public static final String EXTRA_RESOURCE_OVERRIDES = "extra_resource_overrides";
    /** 可选：在应用外部资源前先 reset+load 内置（replace 时常开） */
    public static final String EXTRA_RELOAD_RESOURCE = "extra_reload_resource";

    private static final String TAG = MainHook.TAG;

    public final String mode;
    public final String resourcePath;
    public final String overridesPath;

    public ResourcePathConfig(String mode, String resourcePath, String overridesPath) {
        this.mode = (mode == null || mode.isEmpty()) ? MODE_APPEND : mode.trim().toLowerCase();
        this.resourcePath = emptyToNull(resourcePath);
        this.overridesPath = emptyToNull(overridesPath);
    }

    public boolean isReplace() {
        return MODE_REPLACE.equals(mode);
    }

    public List<String> loadParents() {
        List<String> out = new ArrayList<>(2);
        if (resourcePath != null && hasResourceDir(resourcePath)) {
            out.add(resourcePath);
        } else if (resourcePath != null) {
            Log.w(TAG, "extra_resource_path missing resource/: " + resourcePath);
        }
        if (overridesPath != null && hasResourceDir(overridesPath)) {
            out.add(overridesPath);
        } else if (overridesPath != null) {
            Log.w(TAG, "extra_resource_overrides missing resource/: " + overridesPath);
        }
        return out;
    }

    public static boolean hasResourceDir(String parent) {
        return parent != null && new File(parent, "resource").isDirectory();
    }

    /**
     * 仅当 Intent 带有 {@link #EXTRA_RESOURCE_PATH} 时返回配置，否则 null（走 Meow 内置）。
     */
    public static ResourcePathConfig fromIntentOrNull(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_RESOURCE_PATH)) {
            return null;
        }
        String path = intent.getStringExtra(EXTRA_RESOURCE_PATH);
        if (path == null || path.trim().isEmpty()) {
            Log.w(TAG, "extra_resource_path empty → ignore external resource");
            return null;
        }
        String mode = intent.hasExtra(EXTRA_RESOURCE_MODE)
                ? intent.getStringExtra(EXTRA_RESOURCE_MODE)
                : MODE_APPEND;
        String overrides = intent.hasExtra(EXTRA_RESOURCE_OVERRIDES)
                ? intent.getStringExtra(EXTRA_RESOURCE_OVERRIDES)
                : null;
        ResourcePathConfig cfg = new ResourcePathConfig(mode, path.trim(), overrides);
        Log.i(TAG, "Intent external resource mode=" + cfg.mode
                + " path=" + cfg.resourcePath
                + " overrides=" + cfg.overridesPath);
        return cfg;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
