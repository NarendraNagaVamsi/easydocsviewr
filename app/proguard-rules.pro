# JavaScript bridge used by the Excel WebView
-keepclassmembers class com.easydocs.viewer.ExcelActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
