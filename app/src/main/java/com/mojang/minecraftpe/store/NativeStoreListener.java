package com.mojang.minecraftpe.store;

/** Declaration-only JNI shell for Stage 3.8. */
public class NativeStoreListener {
    public native void onPurchaseCanceled(long requestId, String productId);
    public native void onPurchaseFailed(long requestId, String productId);
    public native void onPurchasePending(long requestId, String productId);
    public native void onPurchasePlatformStoreFailed(
            long requestId,
            String productId,
            String message);
    public native void onPurchaseSuccessful(
            long requestId,
            String productId,
            String receipt,
            String signature);
    public native void onQueryProductsFail(long requestId);
    public native void onQueryProductsSuccess(long requestId, Product[] products);
    public native void onQueryPurchasesFail(long requestId);
    public native void onQueryPurchasesSuccess(long requestId, Purchase[] purchases);
    public native void onStoreInitialized(long requestId, boolean successful);
}

class Product {
}

class Purchase {
}
