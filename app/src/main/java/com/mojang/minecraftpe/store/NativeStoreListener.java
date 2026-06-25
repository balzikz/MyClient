package com.mojang.minecraftpe.store;

public class NativeStoreListener {
    public native void onPurchaseCanceled(long a, String b);
    public native void onPurchaseFailed(long a, String b);
    public native void onPurchasePending(long a, String b);
    public native void onPurchasePlatformStoreFailed(long a, String b, String c);
    public native void onPurchaseSuccessful(long a, String b, String c, String d);
    public native void onQueryProductsFail(long a);
    public native void onQueryProductsSuccess(long a, Product[] b);
    public native void onQueryPurchasesFail(long a);
    public native void onQueryPurchasesSuccess(long a, Purchase[] b);
    public native void onStoreInitialized(long a, boolean b);
}
