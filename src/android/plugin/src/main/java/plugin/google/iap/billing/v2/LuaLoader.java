package plugin.google.iap.billing.v2;

import android.util.Base64;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.ansca.corona.purchasing.StoreServices;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import plugin.google.iap.billing.v2.util.Security;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, PurchasesUpdatedListener {
    private int fLibRef;
    private int fListener;
    private CoronaRuntimeTaskDispatcher fDispatcher;
    private boolean fSetupSuccessful;
    private String fLicenseKey;
    private BillingClient fBillingClient;
    private static final HashMap<String, ProductDetails> fCachedProductDetails = new HashMap<String, ProductDetails>();

    private final HashSet<String> fConsumedPurchases = new HashSet<String>();
    private final HashSet<String> fAcknowledgedPurchases = new HashSet<String>();

    private final QueryPurchasesParams INAPP = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build();
    private final QueryPurchasesParams SUBS =  QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build();
    private int numOfRestoreResults = 0; //Used to make sure both restore events run
    private HashSet<Purchase> cachePurchases = null; //Used for Ansyc Events
    private static final HashMap<String, Number> prorationMode = new HashMap<String, Number>(){{
        put("unknownSubscriptionUpgrade", BillingFlowParams.ProrationMode.UNKNOWN_SUBSCRIPTION_UPGRADE_DOWNGRADE_POLICY);
        put("deferred", BillingFlowParams.ProrationMode.DEFERRED);
        put("immediateAndChargeFullPrice", BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_FULL_PRICE);
        put("immediateAndChargeProratedPrice", BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_FULL_PRICE);
        put("immediateWithoutProration", BillingFlowParams.ProrationMode.IMMEDIATE_WITHOUT_PRORATION);
        put("immediateWithTimeProration", BillingFlowParams.ProrationMode.IMMEDIATE_WITH_TIME_PRORATION);
    }};

    static String GetPurchaseType(String productId) {
        ProductDetails details = fCachedProductDetails.get(productId);
        if (details != null) {
            return details.getProductType();
        }
        return "unknown";
    }

    private boolean initSuccessful() {
        return fBillingClient != null && fSetupSuccessful;
    }

    /**
     * Warning! This method is not called on the main UI thread.
     */
    @Override
    public int invoke(LuaState L) {
        fDispatcher = new CoronaRuntimeTaskDispatcher(L);

        fSetupSuccessful = false;

        // Add functions to library
        NamedJavaFunction[] luaFunctions = new NamedJavaFunction[]{
                new InitWrapper(),
                new LoadProductsWrapper(),
                new PurchaseWrapper(),
                new ConsumePurchaseWrapper(),
                new PurchaseSubscriptionWrapper(),
                new FinishTransactionWrapper(),
                new RestoreWrapper(),
        };

        String libName = L.toString(1);
        L.register(libName, luaFunctions);

        L.pushValue(-1);
        fLibRef = L.ref(LuaState.REGISTRYINDEX);

        L.pushBoolean(true);
        L.setField(-2, "canLoadProducts");

        L.pushBoolean(true);
        L.setField(-2, "canMakePurchases");

        L.pushBoolean(false);
        L.setField(-2, "isActive");

        L.pushBoolean(false);
        L.setField(-2, "canPurchaseSubscriptions");

        L.pushString(StoreServices.getTargetedAppStoreName());
        L.setField(-2, "target");

        return 1;
    }


    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> list) {
        if (list != null) {
            for (Purchase purchase : list) {

                if (Security.verifyPurchase(fLicenseKey, purchase.getOriginalJson(), purchase.getSignature())) {
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                } else {
                    Log.e("Corona", "Signature verification failed!");
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "verificationFailed", fListener));
                }
            }
        } else {
            fDispatcher.send(new StoreTransactionRuntimeTask(null, billingResult, fListener));
        }
    }

    private int init(LuaState L) {
        int listenerIndex = 1;

        L.getGlobal("require");
        L.pushString("config");
        L.call(1, LuaState.MULTRET);

        //gets the application table
        L.getGlobal("application");
        if (L.type(-1) == LuaType.TABLE) {
            //push the license table to the top of the stack
            L.getField(-1, "license");
            if (L.type(-1) == LuaType.TABLE) {
                //push the google table to the top of the stack
                L.getField(-1, "google");
                if (L.type(-1) == LuaType.TABLE) {
                    //gets the key field from the google table
                    L.getField(-1, "key");
                    if (L.type(-1) == LuaType.STRING) {
                        fLicenseKey = L.toString(-1);
                    }
                    L.pop(1);
                }
                L.pop(1);
            }
            L.pop(1);
        }
        L.pop(1);

        // Skip an initial string parameter if present to be compatible with old store.init() API
        if (L.type(listenerIndex) == LuaType.STRING) {
            listenerIndex++;
        }

        fListener = CoronaLua.REFNIL;
        if (CoronaLua.isListener(L, listenerIndex, "storeTransaction")) {
            fListener = CoronaLua.newRef(L, listenerIndex);
        }

        CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
        if (activity != null) {
            fBillingClient = BillingClient.newBuilder(activity).enablePendingPurchases().setListener(this).build();
            fBillingClient.startConnection(new BillingClientStateListener() {
                int listener;

                {
                    listener = fListener;
                }

                @Override
                public void onBillingSetupFinished(BillingResult billingResult) {
                    if (listener != CoronaLua.REFNIL) {
                        InitRuntimeTask task = new InitRuntimeTask(billingResult, listener, fLibRef);// ProductListRuntimeTask(inv, managedProducts, finalSubscriptionProducts, result, listener);
                        fDispatcher.send(task);
                    }
                    listener = CoronaLua.REFNIL;
                    fSetupSuccessful = billingResult.getResponseCode() == BillingResponseCode.OK;
                }

                @Override
                public void onBillingServiceDisconnected() {
                    // ...
                }
            });
        } else {
            fBillingClient = null;
        }

        return 0;
    }


    private int loadProducts(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to load products.");
            return 0;
        }

        int managedProductsTableIndex = 1;
        int listenerIndex = 2;

        final HashSet<String> managedProducts = new HashSet<String>();
        List<QueryProductDetailsParams.Product> inAppProductsList = new ArrayList<>();
        if (L.isTable(managedProductsTableIndex)) {
            int managedProductsLength = L.length(managedProductsTableIndex);
            for (int i = 1; i <= managedProductsLength; i++) {
                L.rawGet(managedProductsTableIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    managedProducts.add(L.toString(-1));
                    QueryProductDetailsParams.Product myProduct = QueryProductDetailsParams.Product.newBuilder().setProductId(L.toString(-1)).setProductType(BillingClient.ProductType.INAPP).build();
                    inAppProductsList.add(myProduct);
                }
                L.pop(1);
            }
        } else {
            Log.e("Corona", "Missing product table to store.loadProducts");
        }

        final HashSet<String> subscriptionProducts = new HashSet<String>();
        List<QueryProductDetailsParams.Product> SubProductsList = new ArrayList<>();
        if (!CoronaLua.isListener(L, listenerIndex, "productList") && L.isTable(listenerIndex)) {
            int subscriptionProductsLength = L.length(listenerIndex);
            for (int i = 1; i <= subscriptionProductsLength; i++) {
                L.rawGet(listenerIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    subscriptionProducts.add(L.toString(-1));
                    QueryProductDetailsParams.Product myProduct = QueryProductDetailsParams.Product.newBuilder().setProductId(L.toString(-1)).setProductType(BillingClient.ProductType.SUBS).build();
                    SubProductsList.add(myProduct);
                }
                L.pop(1);
            }
            listenerIndex++;
        }

        final int listener = CoronaLua.isListener(L, listenerIndex, "productList") ? CoronaLua.newRef(L, listenerIndex) : CoronaLua.REFNIL;
        final List<ProductDetails> allDetails = new ArrayList<ProductDetails>();
        final BillingResult.Builder result = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK);

        final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        final ProductDetailsResponseListener responder = new ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> list) {
                if (billingResult.getResponseCode() != BillingResponseCode.OK && result.build().getResponseCode() == BillingResponseCode.OK) {
                    result.setResponseCode(billingResult.getResponseCode());
                    result.setDebugMessage(billingResult.getDebugMessage());
                }
                if (list != null) {
                    allDetails.addAll(list);
                    for (ProductDetails details : list) {
                        fCachedProductDetails.put(details.getProductId(), details);
                    }
                }
                waiter.Hit();
            }
        };


        int tasks = 0;
        if(!inAppProductsList.isEmpty()){
            tasks++;
            QueryProductDetailsParams detailsParams = QueryProductDetailsParams.newBuilder().setProductList(inAppProductsList).build();
            fBillingClient.queryProductDetailsAsync(detailsParams,responder);
        }
        if(!SubProductsList.isEmpty()){
            tasks++;
            QueryProductDetailsParams detailsParams = QueryProductDetailsParams.newBuilder().setProductList(SubProductsList).build();
            fBillingClient.queryProductDetailsAsync(detailsParams,responder);
        }


        waiter.Set(tasks, new Runnable() {
            @Override
            public void run() {
                fDispatcher.send(new ProductListRuntimeTask(allDetails, managedProducts, subscriptionProducts, result.build(), listener));
            }
        });

        return 0;
    }


    private int restore(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to restore products.");
            return 0;
        }
        numOfRestoreResults = 0;

        final BillingResult.Builder res = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK);

        final ArrayList<Purchase> purchases = new ArrayList<Purchase>();


        final CoronaRuntimeTask restoreCompletedTask =new CoronaRuntimeTask() {
            @Override
            public void executeUsing(CoronaRuntime coronaRuntime) {
                if (fListener == CoronaLua.REFNIL || numOfRestoreResults < 2) {
                    return;
                }
                LuaState L = coronaRuntime.getLuaState();
                try {
                    CoronaLua.newEvent(L, "storeTransaction");
                    L.newTable();

                    L.pushString("restore");
                    L.setField(-2, "type");

                    L.pushString("restoreCompleted");
                    L.setField(-2, "state");

                    L.setField(-2, "transaction");

                    CoronaLua.dispatchEvent(L, fListener, 0);
                } catch (Exception ex) {
                    Log.e("Corona", "StoreTransactionRuntimeTask: dispatching Google IAP storeTransaction event", ex);
                }
            }
        };

        fBillingClient.queryPurchasesAsync(SUBS, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {

                if (res.build().getResponseCode() == BillingResponseCode.OK) {
                    res.setResponseCode(billingResult.getResponseCode());
                    res.setDebugMessage(billingResult.getDebugMessage());
                }
                if(list != null){
                    purchases.addAll(list);
                }

                if(numOfRestoreResults >= 1){
                    onPurchasesUpdated(res.build(), res.build().getResponseCode() == BillingResponseCode.OK ? purchases : null);
                    fDispatcher.send(restoreCompletedTask);
                }
                numOfRestoreResults = numOfRestoreResults+1;
            }
        });

        fBillingClient.queryPurchasesAsync(INAPP, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                numOfRestoreResults = numOfRestoreResults+1;
                if (res.build().getResponseCode() == BillingResponseCode.OK) {
                    res.setResponseCode(billingResult.getResponseCode());
                    res.setDebugMessage(billingResult.getDebugMessage());
                }
                if(list != null){
                    purchases.addAll(list);
                }
                if(numOfRestoreResults >= 1){
                    onPurchasesUpdated(res.build(), res.build().getResponseCode() == BillingResponseCode.OK ? purchases : null);
                    fDispatcher.send(restoreCompletedTask);
                }
                numOfRestoreResults = numOfRestoreResults+1;
            }
        });




        return 0;
    }

    private int purchaseType( LuaState L, final QueryPurchasesParams type) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to purchase products.");
            return 0;
        }

        final String productId;
        if (L.type(1) == LuaType.STRING) {
            productId = L.toString(1);
        } else {
            productId = null;
        }
        final int hashFlags = Base64.NO_PADDING | Base64.URL_SAFE | Base64.NO_CLOSE | Base64.NO_WRAP;
        String productIdType = "inapp";
        if (productId == null) return 0;
        final BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder();
        if(L.isTable(2)) {
            L.getField(2, "accountId");
            if(L.type(-1) == LuaType.STRING) {
                try {
                    String s = L.toString(-1);
                    String hashed = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes()), hashFlags);
                    purchaseParams.setObfuscatedAccountId(hashed);
                } catch (Throwable err) {
                    Log.e("Corona", "Error while hashing accountId: " + err.toString());
                }
            }
            L.pop(1);
            L.getField(2, "profileId");
            if(L.type(-1) == LuaType.STRING) {
                try {
                    String s = L.toString(-1);
                    String hashed = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes()), hashFlags);
                    purchaseParams.setObfuscatedProfileId(hashed);
                } catch (Throwable err) {
                    Log.e("Corona", "Error while hashing accountId: " + err.toString());
                }
            }
            L.pop(1);
            L.getField(2, "obfuscatedAccountId");
            if(L.type(-1) == LuaType.STRING) {
                purchaseParams.setObfuscatedAccountId(L.toString(-1));
            }
            L.pop(1);
            L.getField(2, "obfuscatedProfileId");
            if(L.type(-1) == LuaType.STRING) {
                purchaseParams.setObfuscatedProfileId(L.toString(-1));
            }
            L.pop(1);
            L.getField(2, "offerPersonalized");
            if(L.type(-1) == LuaType.BOOLEAN) {
                purchaseParams.setIsOfferPersonalized(L.toBoolean(-1));
            }
            L.pop(1);
            L.getField(2, "subscriptionUpdate");
            if(L.type(-1) == LuaType.TABLE) {
                BillingFlowParams.SubscriptionUpdateParams.Builder subscriptionUpdateBuilder = BillingFlowParams.SubscriptionUpdateParams.newBuilder();
                L.getField(2, "purchaseToken");
                if(L.type(-1) == LuaType.STRING) {
                    subscriptionUpdateBuilder.setOldPurchaseToken(L.toString(-1));
                }
                L.pop(1);
                L.getField(2, "prorationMode");
                if(L.type(-1) == LuaType.STRING) {
                    if(prorationMode.containsKey(L.toString(-1))){
                        subscriptionUpdateBuilder.setReplaceProrationMode((int) prorationMode.get(L.toString(-1)));
                    }else{
                        Log.e("Corona", "Error Invalid prorationMode type: " +L.toString(-1));
                    }
                }
                L.pop(1);
                purchaseParams.setIsOfferPersonalized(L.toBoolean(-1));
                L.getField(2, "productType");
                if(L.type(-1) == LuaType.STRING) {
                    if(L.toString(-1).equals("subs") || L.toString(-1).equals("inapp")){
                        productIdType = L.toString(-1);
                    }else{
                        Log.e("Corona", "Error Invalid productIdType type: " +L.toString(-1));
                    }
                }
                L.pop(1);
                purchaseParams.setIsOfferPersonalized(L.toBoolean(-1));
            }
            L.pop(1);


        }


        ProductDetails productDetails = fCachedProductDetails.get(productId);
        if (productDetails != null) {
            //Possibly add more products to purchase?
            BillingFlowParams.ProductDetailsParams productDetailsParams =null;
            if(BillingClient.ProductType.SUBS.equals(productDetails.getProductType())){
                productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails).setOfferToken(productDetails.getSubscriptionOfferDetails().get(0).getOfferToken()).build();
            }else{
                productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails).build();
            }
            List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
            productDetailsParamsList.add(productDetailsParams);

            purchaseParams.setProductDetailsParamsList(productDetailsParamsList);
            CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
            if (activity != null) {
                fBillingClient.launchBillingFlow(activity, purchaseParams.build());
            }
        } else {
            List<QueryProductDetailsParams.Product> myProductList = new ArrayList<>();
            QueryProductDetailsParams.Product myProduct = QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(productIdType).build();
            myProductList.add(myProduct);
            QueryProductDetailsParams detailsParams = QueryProductDetailsParams.newBuilder().setProductList(myProductList).build();
            fBillingClient.queryProductDetailsAsync(detailsParams,new ProductDetailsResponseListener() {
                @Override
                public void onProductDetailsResponse(final BillingResult billingResult, List<ProductDetails> list) {
                    boolean sent = false;
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        for (ProductDetails details : list) {
                            fCachedProductDetails.put(details.getProductId(), details);
                            if (details.getProductId().equals(productId)) {
                                BillingFlowParams.ProductDetailsParams productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build();
                                List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
                                productDetailsParamsList.add(productDetailsParams);
                                purchaseParams.setProductDetailsParamsList(productDetailsParamsList);
                                CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
                                if (activity != null) {
                                    fBillingClient.launchBillingFlow(activity, purchaseParams.build());
                                }
                                sent = true;
                                break;
                            }
                        }
                        if (!sent) {
                            Log.e("Corona", "Error while purchasing because Product Id was not found");
                        }
                    } else {
                        Log.e("Corona", "Error while purchasing" + billingResult.getDebugMessage());
                    }
                }
            });

        }

        return 0;
    }

    private int purchaseSubscription(LuaState L) {
        return purchaseType(L, SUBS);
    }

    private int purchase(LuaState L) {
        return purchaseType(L, INAPP);
    }

    private int consumePurchase(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to consume products.");
            return 0;
        }

        Runnable processPurchases = new Runnable() {
            @Override
            public void run() {
                for (final Purchase purchase : cachePurchases) {
                    if (!fConsumedPurchases.contains(purchase.getPurchaseToken())) {
                        fConsumedPurchases.add(purchase.getPurchaseToken());
                        ConsumeParams params = ConsumeParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build();
                        fBillingClient.consumeAsync(params, new ConsumeResponseListener() {
                            @Override
                            public void onConsumeResponse(BillingResult billingResult, String ignore) {
                                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "consumed", fListener));
                                } else {
                                    fConsumedPurchases.remove(purchase.getPurchaseToken());
                                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                                }
                            }
                        });
                    } else {
                        Log.i("Corona", "Product already being consumed, skipping it: " + purchase.getOrderId() + ". It is safe to ignore this message");
                    }
                }
            }
        };
        getPurchasesFromTransaction(L, true, processPurchases);

        return 0;
    }

    private int finishTransaction(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to finishTransaction.");
            return 0;
        }

        Runnable processPurchases = new Runnable() {
            @Override
            public void run() {
                for (final Purchase purchase : cachePurchases) {
                    if (!fAcknowledgedPurchases.contains(purchase.getPurchaseToken())) {
                        if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            fAcknowledgedPurchases.add(purchase.getPurchaseToken());
                            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build();
                            fBillingClient.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                                @Override
                                public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "finished", fListener));
                                    } else {
                                        fAcknowledgedPurchases.remove(purchase.getPurchaseToken());
                                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                                    }
                                }
                            });
                        }
                    } else {
                        Log.i("Corona", "Purchase already being finished (acknowledged)" + purchase.getOrderId() + ". It is safe to ignore this message");
                    }
                }
            }
        };
        getPurchasesFromTransaction(L, true, processPurchases);



        return 0;
    }

    private void getPurchasesFromTransaction(LuaState L, final boolean IAPsOnly, final Runnable task) {
        final HashSet<String> productsIds = new HashSet<String>();
        final HashSet<String> tokens = new HashSet<String>();
        if (L.isTable(1)) {
            int tableLength = L.length(1);
            for (int i = 1; i <= tableLength; i++) {
                L.rawGet(1, i);
                if (L.type(-1) == LuaType.STRING) {
                    productsIds.add(L.toString(-1));
                }
                L.pop(1);
            }

            L.getField(1, "transaction");
            if (L.isTable(-1)) {
                L.getField(-1, "token");
                if (L.type(-1) == LuaType.STRING) {
                    tokens.add(L.toString(-1));
                }
                L.pop(1);

                L.getField(-1, "productIdentifier");
                if (L.type(-1) == LuaType.STRING) {
                    productsIds.add(L.toString(-1));
                }
                L.pop(1);
            }
            L.pop(1);

            L.getField(1, "token");
            if (L.type(-1) == LuaType.STRING) {
                tokens.add(L.toString(-1));
            }
            L.pop(1);

            L.getField(1, "productIdentifier");
            if (L.type(-1) == LuaType.STRING) {
                productsIds.add(L.toString(-1));
            }
            L.pop(1);
        } else {
            if (L.type(1) == LuaType.STRING) {
                productsIds.add(L.toString(1));
            }
        }
        final List<Purchase> allPurchases = new ArrayList<Purchase>();
        final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        int tasks = 0;

        if (!IAPsOnly) {
            tasks++;
            fBillingClient.queryPurchasesAsync(SUBS, new PurchasesResponseListener() {
                @Override
                public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                    if (list != null) {
                        allPurchases.addAll(list);
                    }
                    waiter.Hit();
                }
            });

        }
        tasks++;
        fBillingClient.queryPurchasesAsync(INAPP, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                if (list != null) {
                    allPurchases.addAll(list);
                }
                waiter.Hit();
            }
        });





        waiter.Set(tasks, new Runnable() {
            @Override
            public void run() {
                HashSet<Purchase> purchases = new HashSet<Purchase>();
                for (String productsId : productsIds) {
                    for (Purchase purchase : allPurchases) {
                        for (String purchasePID : purchase.getProducts()) {
                            purchases.add(purchase);
                        }
                    }
                }
                for (String token : tokens) {
                    for (Purchase purchase : allPurchases) {
                        if (token.equals(purchase.getPurchaseToken())) {
                            purchases.add(purchase);
                        }
                    }
                }
                cachePurchases = purchases;
                task.run();
            }
        });



    }

    private class InitWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "init";
        }

        @Override
        public int invoke(LuaState L) {
            return init(L);
        }
    }

    private class LoadProductsWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "loadProducts";
        }

        @Override
        public int invoke(LuaState L) {
            return loadProducts(L);
        }
    }

    private class PurchaseWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "purchase";
        }

        @Override
        public int invoke(LuaState L) {
            return purchase(L);
        }
    }

    private class PurchaseSubscriptionWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "purchaseSubscription";
        }

        @Override
        public int invoke(LuaState L) {
            return purchaseSubscription(L);
        }
    }

    private class ConsumePurchaseWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "consumePurchase";
        }

        @Override
        public int invoke(LuaState L) {
            return consumePurchase(L);
        }
    }

    private class FinishTransactionWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "finishTransaction";
        }

        @Override
        public int invoke(LuaState L) {
            return finishTransaction(L);
        }
    }

    private class RestoreWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "restore";
        }

        @Override
        public int invoke(LuaState L) {
            return restore(L);
        }
    }

}
