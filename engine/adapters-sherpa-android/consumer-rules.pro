# ネイティブのコードが JNI で、クラスとフィールドとメソッドを名前で探す。Kotlin から触っていない設定クラスも探す。
# 見つからないと JNI の検査でプロセスごと落ちる。sherpa-onnx の AAR は keep のルールを持っていない
-keep class com.k2fsa.sherpa.onnx.** { *; }
