package dev.cloneforge.runtime.hooks;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.ContentResolver;
import android.content.SyncAdapterType;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import dev.cloneforge.runtime.CloneRuntimeEngine;

/**
 * Subsystem 9 — AccountAuthenticator & SyncAdapter Virtualization (Spec §8).
 *
 * Prevents account and sync conflicts in Android's central AccountManagerService
 * and SyncManager by isolating account types and content authorities between
 * the original application and its clones:
 *  1. Rewrites accountType query arguments (e.g. org.telegram.messenger ->
 *     org.telegram.messenger.cf001) so the clone accesses its own suffixed accounts.
 *  2. Un-suffixes Account.type in returned arrays so the clone's internal logic
 *     observes the original accountType string and passes strict equality checks.
 *  3. Rewrites Account creation/modification calls (addAccountExplicitly, removeAccount,
 *     setAuthToken, peekAuthToken, setUserData, etc.) to use the cloned accountType.
 *  4. Rewrites ContentResolver sync adapter requests (requestSync, setIsSyncable,
 *     getSyncAutomatically, etc.) across both accountType and contentAuthority.
 *  5. Dual-layer defense: uses LSPlant method replacement when active, coupled with
 *     an IPC ServiceManager.sCache ("account") proxy that intercepts raw Binder calls.
 */
public final class AccountManagerHook {

    private static final String TAG = "CloneForgeRuntime";

    private static String sOrigPkg = null;
    private static String sClonePkg = null;
    private static String sCloneSuffix = null;

    private static final Map<String, String> sAccountTypeMap = new HashMap<>();
    private static final Map<String, String> sReverseAccountTypeMap = new HashMap<>();
    private static final Map<String, String> sAuthorityMap = new HashMap<>();
    private static final Map<String, String> sReverseAuthorityMap = new HashMap<>();

    private static volatile boolean sInstalled = false;

    private AccountManagerHook() {
    }

    /**
     * Installs AccountManager and ContentResolver virtualization hooks.
     */
    public static synchronized void install(
            String origPkg,
            String clonePkg,
            String cloneSuffix,
            Map<String, String> accountTypeMap,
            Map<String, String> authorityMap
    ) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        sOrigPkg = origPkg;
        sClonePkg = clonePkg;
        sCloneSuffix = cloneSuffix;

        if (accountTypeMap != null) {
            sAccountTypeMap.putAll(accountTypeMap);
            for (Map.Entry<String, String> entry : accountTypeMap.entrySet()) {
                sReverseAccountTypeMap.put(entry.getValue(), entry.getKey());
            }
        }

        // Even if accountTypeMap was not pre-populated in config.json, auto-register standard
        // package-based account types (common pattern in Telegram, Brave, etc.)
        if (sAccountTypeMap.isEmpty() && origPkg != null && cloneSuffix != null) {
            String defaultType = origPkg + ".account";
            String defaultCloneType = defaultType + "." + cloneSuffix;
            sAccountTypeMap.put(defaultType, defaultCloneType);
            sReverseAccountTypeMap.put(defaultCloneType, defaultType);

            // Also map package name itself as an account type
            sAccountTypeMap.put(origPkg, clonePkg);
            sReverseAccountTypeMap.put(clonePkg, origPkg);
        }

        if (authorityMap != null) {
            sAuthorityMap.putAll(authorityMap);
            for (Map.Entry<String, String> entry : authorityMap.entrySet()) {
                sReverseAuthorityMap.put(entry.getValue(), entry.getKey());
            }
        }

        // 1. ServiceManager Binder proxy (pure reflection, works on all API levels)
        installServiceManagerProxy();

        // 2. LSPlant ART method hooks (when LSPlant is active)
        if (CloneRuntimeEngine.HAS_LSPLANT) {
            installLsplantHooks();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Translation Helpers
    // ---------------------------------------------------------------------------------------------

    public static Account toClonedAccount(Account orig) {
        if (orig == null) return null;
        String mappedType = sAccountTypeMap.get(orig.type);
        if (mappedType != null && !mappedType.equals(orig.type)) {
            return new Account(orig.name, mappedType);
        }
        return orig;
    }

    public static Account toOrigAccount(Account cloned) {
        if (cloned == null) return null;
        String origType = sReverseAccountTypeMap.get(cloned.type);
        if (origType != null && !origType.equals(cloned.type)) {
            return new Account(cloned.name, origType);
        }
        return cloned;
    }

    private static Account[] mapAccountsToOrig(Account[] accounts) {
        if (accounts == null || accounts.length == 0) return accounts;
        Account[] result = new Account[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            result[i] = toOrigAccount(accounts[i]);
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Layer 1: LSPlant ART Hooks
    // ---------------------------------------------------------------------------------------------

    private static void installLsplantHooks() {
        int hooked = 0;

        // --- AccountManager hooks ---
        try {
            Class<?> amClass = AccountManager.class;

            // getAccountsByType(String)
            for (Method m : amClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("getAccountsByType".equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof String) {
                            String type = (String) args[1];
                            String mapped = sAccountTypeMap.get(type);
                            if (mapped != null) args[1] = mapped;
                        }
                        Object res = hooker.invokeBackup(args);
                        return (res instanceof Account[]) ? mapAccountsToOrig((Account[]) res) : res;
                    }) != null) hooked++;
                }

                // getAccounts()
                if ("getAccounts".equals(name) && m.getParameterCount() == 0) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        Object res = hooker.invokeBackup(args);
                        return (res instanceof Account[]) ? mapAccountsToOrig((Account[]) res) : res;
                    }) != null) hooked++;
                }

                // getAccountsByTypeForPackage(String, String)
                if ("getAccountsByTypeForPackage".equals(name) && m.getParameterCount() == 2) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 2) {
                            if (args[1] instanceof String) {
                                String mapped = sAccountTypeMap.get((String) args[1]);
                                if (mapped != null) args[1] = mapped;
                            }
                            if (sOrigPkg != null && sOrigPkg.equals(args[2])) {
                                args[2] = sClonePkg;
                            }
                        }
                        Object res = hooker.invokeBackup(args);
                        return (res instanceof Account[]) ? mapAccountsToOrig((Account[]) res) : res;
                    }) != null) hooked++;
                }

                // addAccountExplicitly(Account, String, Bundle)
                if ("addAccountExplicitly".equals(name) && m.getParameterCount() >= 2) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof Account) {
                            args[1] = toClonedAccount((Account) args[1]);
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // removeAccountExplicitly(Account)
                if ("removeAccountExplicitly".equals(name) && m.getParameterCount() >= 1) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof Account) {
                            args[1] = toClonedAccount((Account) args[1]);
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // setAuthToken(Account, String, String)
                if ("setAuthToken".equals(name) && m.getParameterCount() == 3) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof Account) {
                            args[1] = toClonedAccount((Account) args[1]);
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // peekAuthToken(Account, String)
                if ("peekAuthToken".equals(name) && m.getParameterCount() == 2) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof Account) {
                            args[1] = toClonedAccount((Account) args[1]);
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // getUserData(Account, String)
                if ("getUserData".equals(name) && m.getParameterCount() == 2) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof Account) {
                            args[1] = toClonedAccount((Account) args[1]);
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // setUserData(Account, String, String)
                if ("setUserData".equals(name) && m.getParameterCount() == 3) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof Account) {
                            args[1] = toClonedAccount((Account) args[1]);
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // invalidateAuthToken(String, String)
                if ("invalidateAuthToken".equals(name) && m.getParameterCount() == 2) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length > 1 && args[1] instanceof String) {
                            String mapped = sAccountTypeMap.get((String) args[1]);
                            if (mapped != null) args[1] = mapped;
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed hooking AccountManager via LSPlant", t);
        }

        // --- ContentResolver sync hooks ---
        try {
            Class<?> crClass = ContentResolver.class;
            for (Method m : crClass.getDeclaredMethods()) {
                String name = m.getName();

                // Static methods taking (Account, String authority, ...)
                if (("requestSync".equals(name) || "setIsSyncable".equals(name)
                        || "getIsSyncable".equals(name) || "setSyncAutomatically".equals(name)
                        || "getSyncAutomatically".equals(name) || "addPeriodicSync".equals(name)
                        || "removePeriodicSync".equals(name) || "getPeriodicSyncs".equals(name))
                        && Modifier.isStatic(m.getModifiers())) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        if (args != null && args.length >= 2) {
                            if (args[0] instanceof Account) {
                                args[0] = toClonedAccount((Account) args[0]);
                            }
                            if (args[1] instanceof String) {
                                String mappedAuth = sAuthorityMap.get((String) args[1]);
                                if (mappedAuth != null) args[1] = mappedAuth;
                            }
                        }
                        return hooker.invokeBackup(args);
                    }) != null) hooked++;
                }

                // getSyncAdapterTypes()
                if ("getSyncAdapterTypes".equals(name) && Modifier.isStatic(m.getModifiers())) {
                    if (Hooker.hook(m, (hooker, args) -> {
                        Object res = hooker.invokeBackup(args);
                        if (res instanceof SyncAdapterType[]) {
                            SyncAdapterType[] types = (SyncAdapterType[]) res;
                            SyncAdapterType[] mapped = new SyncAdapterType[types.length];
                            for (int i = 0; i < types.length; i++) {
                                SyncAdapterType st = types[i];
                                String origAuth = sReverseAuthorityMap.get(st.authority);
                                String origType = sReverseAccountTypeMap.get(st.accountType);
                                if (origAuth != null || origType != null) {
                                    mapped[i] = SyncAdapterType.newKey(
                                            origAuth != null ? origAuth : st.authority,
                                            origType != null ? origType : st.accountType
                                    );
                                } else {
                                    mapped[i] = st;
                                }
                            }
                            return mapped;
                        }
                        return res;
                    }) != null) hooked++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed hooking ContentResolver sync methods via LSPlant", t);
        }

        Log.i(TAG, "AccountManagerHook: " + hooked + " LSPlant ART hooks installed");
    }

    // ---------------------------------------------------------------------------------------------
    // Layer 2: ServiceManager sCache IPC Proxy
    // ---------------------------------------------------------------------------------------------

    private static void installServiceManagerProxy() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            final IBinder realAccountBinder = (IBinder) getService.invoke(null, "account");
            if (realAccountBinder == null) {
                Log.w(TAG, "ServiceManager.getService('account') returned null");
                return;
            }

            Field sCacheField = smClass.getDeclaredField("sCache");
            sCacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, IBinder> sCache = (Map<String, IBinder>) sCacheField.get(null);
            if (sCache == null) {
                return;
            }

            final Class<?> iAccountManagerClass = Class.forName("android.accounts.IAccountManager");
            Class<?> stubClass = Class.forName("android.accounts.IAccountManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            final Object realIAccountManager = asInterface.invoke(null, realAccountBinder);

            // Proxy IAccountManager
            final Object proxyIAccountManager = Proxy.newProxyInstance(
                    iAccountManagerClass.getClassLoader(),
                    new Class<?>[]{iAccountManagerClass, IInterface.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String mName = method.getName();
                            try {
                                // Rewrite query arguments
                                if (("getAccountsAsUser".equals(mName) || "getAccounts".equals(mName))
                                        && args != null && args.length > 0 && args[0] instanceof String) {
                                    String type = (String) args[0];
                                    String mapped = sAccountTypeMap.get(type);
                                    if (mapped != null) args[0] = mapped;
                                }

                                if (("addAccountExplicitly".equals(mName) || "removeAccountExplicitly".equals(mName))
                                        && args != null && args.length > 0 && args[0] instanceof Account) {
                                    args[0] = toClonedAccount((Account) args[0]);
                                }

                                Object result = method.invoke(realIAccountManager, args);

                                // Restore returned account types
                                if (result instanceof Account[]) {
                                    return mapAccountsToOrig((Account[]) result);
                                }
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException ite) {
                                Throwable cause = ite.getCause();
                                throw (cause != null) ? cause : ite;
                            }
                        }
                    }
            );

            // Proxy the IBinder returned by ServiceManager
            IBinder proxyBinder = (IBinder) Proxy.newProxyInstance(
                    IBinder.class.getClassLoader(),
                    new Class<?>[]{IBinder.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String mName = method.getName();
                            if ("queryLocalInterface".equals(mName)) {
                                return proxyIAccountManager;
                            }
                            try {
                                return method.invoke(realAccountBinder, args);
                            } catch (java.lang.reflect.InvocationTargetException ite) {
                                Throwable cause = ite.getCause();
                                throw (cause != null) ? cause : ite;
                            }
                        }
                    }
            );

            sCache.put("account", proxyBinder);
            Log.i(TAG, "ServiceManager.sCache['account'] binder proxy installed");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install ServiceManager account proxy (fallback to LSPlant)", t);
        }
    }
}
