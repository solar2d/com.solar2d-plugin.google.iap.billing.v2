# event.transaction

> --------------------- ------------------------------------------------------------------------------------------
> __Type__              [Table][api.type.Table]
> __Event__             [storeTransaction][plugin.google-iap-billing-v2.event.storeTransaction]
> __Revision__          [REVISION_LABEL](REVISION_URL)
> __Keywords__          Google, IAP, in-app purchases, storeTransaction, transaction
> __See also__			[storeTransaction][plugin.google-iap-billing-v2.event.storeTransaction]
>						[store.*][plugin.google-iap-billing-v2]
> --------------------- ------------------------------------------------------------------------------------------

## Overview

This table contains the following <nobr>read-only</nobr> properties pertaining to the transaction:

* `state` &mdash; A [string][api.type.String] indicating the state of the transaction.

> --------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
> `"purchased"`         this will be in the case of a normal or restored purchase
> `"pending"`           for pending purchases, when Play Store is awaiting for user to make a payment in physical store. Once purchase is made, another event would be generated with the `"purchased"` state.
> `"cancelled"`         for a purchase cancelled by user. Note, this event would not contain specifics about which purchase was cancelled
> `"consumed"`          for a consumed purchase
> `"finished"`          when purchase was acknowledged successfully
> `"failed"`            in case of an error. Note, this event probably would not contain specifics about which purchase failed, only the error message and the code
> `"restoreCompleted"`  once all purchase(s) are restored, after the [restore()][plugin.google-iap-billing-v2.restore] call
> `"unknown"`           this should not happen
> --------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

* `receipt` &mdash; A <nobr>JSON-formatted</nobr> [string][api.type.String] representation of the transaction receipt. This is the `INAPP_PURCHASE_DATA` returned by Google.

* `identifier` &mdash; The unique [string][api.type.String] identifier for the transaction.

* `productIdentifier` &mdash; A [string][api.type.String] representing the product identifier associated with the transaction.

* `date` &mdash; A [string][api.type.String] representing the date when the transaction occurred, represented in Unix epoch milliseconds.

* `packageName` &mdash; A [string][api.type.String] representing the application package from which the purchase originated.

* `token` &mdash; A token ([string][api.type.String]) that uniquely identifies a purchase for a given item and user pair.

* `originalJson` &mdash; A JSON-formatted [string][api.type.String] representation of the transaction.

* `signature` &mdash; A digital signature [string][api.type.String] that can be used to verify the purchase. This is the `INAPP_DATA_SIGNATURE` returned by Google.

* `isError` &mdash; [Boolean][api.type.Boolean] value indicating whether an error occurred. If this is `true`, `errorType` and `errorString` will be [strings][api.type.String] stating the reason.

* `errorType` &mdash; A [string][api.type.String] representing the type of error that occurred if `isError` is `true`.

* `errorString` &mdash; A more descriptive error message ([string][api.type.String]) if `isError` is `true`.
