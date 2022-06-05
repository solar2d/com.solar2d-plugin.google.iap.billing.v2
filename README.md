# plugin.google-iap-billing.v2

This plugin allows you to support in-app purchases using Google's in-app billing service.
For more information see the In-App Purchases (IAP) guide.

## Using in Simulator project

Add following to your build settings
```lua
    plugins = {
        ["plugin.google.iap.billing.v2"] = {
            publisherId = "com.solar2d",
        },
    },
```
For how to use the plugin, see the [API Reference](http://docs.coronalabs.com/plugin/google-iap-billing/index.html).

## Setup

Drag&Drop `android` directory onto Android Studio dock icon.

## Building the plugin

Execute `:plugin:exportPluginJar`: from android directory run: `./gradlew :plugin:exportPluginJar`.

Result should be in `plugin/build/outputs/jar/plugin.google.iap.billing.v2.jar`.


## Platform Support

* Android


## Resources

* [Solar2D Native Reference](http://docs.coronalabs.com/native/)
* [API Reference](http://docs.coronalabs.com/plugin/google-iap-billing/index.html)
