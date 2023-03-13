package eu.faircode.netguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// HeartGuard code - get and process rules from SQL, make decisions
public class RulesManager {
    private static final String TAG = "NetGuard.Database";

    public static final String ACTION_RULES_UPDATE = "eu.faircode.netguard.RULES_UPDATE";

    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private static RulesManager global_rm = null;

    private boolean enabled = true;
    private long nextEnableToggle;
    private ExecutorService executor = Executors.newCachedThreadPool();

    private Map<String, Boolean> allowedPackages = new HashMap<String, Boolean>();

    public static RulesManager getInstance(Context context) {
        if (global_rm == null) {
            global_rm = new RulesManager(context);
        }
        return global_rm;
    }

    public RulesManager(Context context) {
        //This will start a testing mode that toggles enabled
        //setNextEnabledToggle(context);
    }

    // Convenience functions to throw exceptions if a rule uses the same phrase twice
    private void putNewInt(Bundle bundle, String key, int value) {
        if (bundle.containsKey(key)) {
            throw new AssertionError("Already contains key " + key);
        }
        bundle.putInt(key, value);
    }
    private void putNewString(Bundle bundle, String key, String value) {
        if (bundle.containsKey(key)) {
            throw new AssertionError("Already contains key " + key);
        }
        bundle.putString(key, value);
    }

    // Somewhat general function to parse an allow rule into a bundle of all phrases
    private Bundle parseAllowTextToBundle(Context context, String text) {
        Bundle data_bundle = new Bundle();

        Pattern p = Pattern.compile("allow (.*)");
        Matcher m = p.matcher(text);
        if (!m.matches())
            return null;
        String constraints = m.group(1);

        String[] separated = constraints.split(" ");

        for (String phrase : separated) {
            m = Pattern.compile("packagename:(.*)").matcher(phrase);
            if (m.matches()) {
                String packagename = m.group(1);
                try {
                    int uid = context.getPackageManager().getApplicationInfo(packagename, 0).uid;
                    putNewInt(data_bundle, "uid", uid);
                    putNewString(data_bundle, "packagename", packagename);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "package " + packagename + " not found");
                    return null;
                }
            }

            m = Pattern.compile("host:(.+)").matcher(phrase);
            if (m.matches()) {
                putNewString(data_bundle, "host", m.group(1));
            }

            m = Pattern.compile("ipv4:(.+)").matcher(phrase);
            if (m.matches()) {
                putNewString(data_bundle, "ipv4", m.group(1));
            }
        }

        return data_bundle;
    }

    // Returns a RuleAndUid for whitelist rules
    // (Only applies to a rule which allows a hostname/IP and optionally a package name.
    // Does not apply to rules which enable a package unconditionally.)
    private RuleAndUid parseTextToWhitelistRule(Context context, String text) {
        Bundle data_bundle = parseAllowTextToBundle(context, text);

        if (data_bundle == null)
            return null;

        int uid = 0;
        if (data_bundle.containsKey("uid")) {
            uid = data_bundle.getInt("uid");
        }

        if (data_bundle.containsKey("host"))
        {
            if (data_bundle.containsKey("ipv4")) {
                Log.e(TAG, "Rule string " + text + " has invalid combination of types");
                return null;
            }
            return new RuleAndUid(uid, new DomainRule(data_bundle.getString("host"), 1));
        }

        if (data_bundle.containsKey("ipv4"))
        {
            if (data_bundle.containsKey("host")) {
                Log.e(TAG, "Rule string " + text + " has invalid combination of types");
                return null;
            }
            return new RuleAndUid(uid, new IPRule(data_bundle.getString("ipv4"), 1));
        }

        // No rule found
        return null;
    }

    public void getCurrentRules(WhitelistManager wm, Context context) {
        lock.readLock().lock();
        DatabaseHelper dh = DatabaseHelper.getInstance(context);

        Cursor cursor = dh.getEnactedRules();
        int col_ruletext = cursor.getColumnIndexOrThrow("ruletext");

        while (cursor.moveToNext()) {
            String ruletext = cursor.getString(col_ruletext);

            RuleAndUid ruleanduid = parseTextToWhitelistRule(context, ruletext);
            if (ruleanduid == null)
                continue;
            if (ruleanduid.uid == RuleAndUid.UID_GLOBAL) {
                wm.addGlobalRule(ruleanduid.rule);
            } else {
                wm.addAppRule(ruleanduid.uid, ruleanduid.rule);
            }
        }

        lock.readLock().unlock();
    }

    public boolean getPreferenceFilter(Context context) {
        return true;
    }

    public boolean getPreferenceEnabled(Context context) {
        return enabled;
    }

    public boolean getPreferenceLogApp(Context context) {
        return true;
    }

    public boolean getPreferenceScreenOnWifi(Context context) {
        return false;
    }

    public boolean getPreferenceScreenOnOther(Context context) {
        return false;
    }

    public boolean getPreferenceScreenOn(Context context) {
        return false;
    }

    // "whitelist_wifi", when true, blocks apps on wifi. Nomenclature skills ???
    public boolean getPreferenceWhitelistWifi(Context context) {
        return true;
    }

    // "whitelist_wifi", when true, blocks apps on other. Nomenclature skills ???
    public boolean getPreferenceWhitelistOther(Context context) {
        return true;
    }

    public boolean getPreferenceLockdown(Context context) {
        return false;
    }

    public boolean getPreferenceLockdownWifi(Context context) {
        return false;
    }

    public boolean getPreferenceLockdownOther(Context context) {
        return false;
    }

    public boolean getPreferenceWhitelistRoaming(Context context) {
        return false;
    }

    public boolean getWifiEnabledForApp(Context context, String packagename, boolean defaultVal) {
        if (allowedPackages.containsKey(packagename)) {
            return allowedPackages.get(packagename);
        }
        return defaultVal;
    }
    public boolean getOtherEnabledForApp(Context context, String packagename, boolean defaultVal) {
        // Identical settings to above
        return getWifiEnabledForApp(context, packagename, defaultVal);
    }
    public boolean getScreenWifiEnabledForApp(Context context, String packagename, boolean defaultVal) {
        // Identical settings to above
        return getWifiEnabledForApp(context, packagename, defaultVal);
    }
    public boolean getScreenOtherEnabledForApp(Context context, String packagename, boolean defaultVal) {
        // Identical settings to above
        return getWifiEnabledForApp(context, packagename, defaultVal);
    }

    private void setAlarmForTime(Context context, long time) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, ServiceSinkhole.class);
        i.setAction(ACTION_RULES_UPDATE);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            pi = PendingIntent.getForegroundService(context, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        else
            pi = PendingIntent.getService(context, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        am.cancel(pi);

        am.set(AlarmManager.RTC_WAKEUP, time, pi);
    }

    private void setNextEnabledToggle(Context context) {
        nextEnableToggle = new Date().getTime() + 5 * 1000L;

        setAlarmForTime(context, nextEnableToggle);
    }

    private void toggleEnabled(Context context) {
        enabled = !enabled;

        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(ActivityMain.ACTION_RULES_CHANGED));
        setNextEnabledToggle(context);
    }

    public void rulesChanged(Context context) {
        toggleEnabled(context);
    }
}
