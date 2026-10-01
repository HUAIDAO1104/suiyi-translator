package app.suiyi.translate;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Device checks for the key-only setup flow, language direction and keyboard-safe controls.
 * Runs without a real API key or a model request. Result fixtures live only in the test APK. */
public final class UiSmokeTest extends Instrumentation {
    private MainActivity activity;
    private boolean requireIme;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);requireIme=arguments!=null&&"true".equals(arguments.getString("require_ime")); start(); }
    @Override public void onStart() {
        Bundle outcome = new Bundle();
        try {
            android.accessibilityservice.AccessibilityServiceInfo service=getUiAutomation().getServiceInfo();service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(service);
            AppSettings settings = new AppSettings(getTargetContext());
            settings.prefs.edit().clear().putBoolean("auto_update", false).putBoolean("auto_speak", false).commit();
            settings.secrets.put("api_key", ""); new HistoryStore(getTargetContext()).clear(true);
            require(!settings.base().isEmpty(), "Default endpoint missing");
            settings.prefs.edit().putString("api_base", "").commit();
            require(settings.base().equals(BuildConfig.DEFAULT_API_BASE), "Empty old endpoint did not migrate");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle(); screenshot("home");
            visible("说中文"); visible("说ไทย");
            click("设置"); visible("连接你的 AI"); screenshot("settings");
            click("展开或收起高级设置"); visible(BuildConfig.DEFAULT_API_BASE); visible("qwen3-max");
            click("返回"); click("说中文"); visible("连接你的 AI");
            setText("API Key", "ui-test-placeholder"); click("保存并开始翻译");
            require(settings.hasApiKey(), "Key was not saved");
            require(!settings.base().isEmpty(), "Saving only key erased default endpoint");
            click("选择对方说的语言"); click("English"); visible("说English");
            click("交换双方语言"); visible("说English"); visible("说中文");
            click("选择我说的语言"); click("中文");
            click("选择对方说的语言"); click("ไทย");
            click("文字"); setText("翻译原文", "你好，请问去机场怎么走？");
            if(requireIme) verifyKeyboardButton();
            visible("翻译文字  →"); screenshot("keyboard");
            click("语音"); visible("说中文");
            // Layout fixture, deliberately never sent to a model endpoint.
            runOnMainSync(() -> {
                try {
                    Method finish = MainActivity.class.getDeclaredMethod("finishTranslation", int.class, GatewayClient.Result.class, Language.class, Language.class);
                    finish.setAccessible(true);
                    finish.invoke(activity, 0, new GatewayClient.Result("你好，请问去机场怎么走？", "สวัสดีครับ ไปสนามบินทางไหนครับ"), Language.values()[0], Language.values()[2]);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            settle(); visible("复制"); visible("收藏"); screenshot("translation-example");
            click("收藏"); visible("已收藏"); click("翻译记录"); visible("翻译记录"); screenshot("history");
            click("收藏"); visible("已收藏"); click("返回");
            click("大字"); visible("朗读给对方听"); screenshot("large-example"); click("返回");
            click("新对话"); visible("译文将出现在这里");
            settings.secrets.put("api_key", ""); new HistoryStore(getTargetContext()).clear(true);
            outcome.putString("result", "PASS: default endpoint migration, key-only setup, two-way language picker, swap, keyboard, favorites, history, large text and new conversation");
            finish(Activity.RESULT_OK, outcome);
        } catch (Throwable failure) {
            outcome.putString("error", failure.toString());
            try { screenshot("failure"); } catch (Exception ignored) {}
            finish(Activity.RESULT_CANCELED, outcome);
        }
    }
    private void settle() { waitForIdleSync(); try { getUiAutomation().waitForIdle(200, 4000); } catch (Exception ignored) {} }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value) {
        if (node == null) return null;
        if (value.contentEquals(node.getText() == null ? "" : node.getText()) || value.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=find(node.getChild(i),value);if(found!=null)return found; }
        return null;
    }
    private AccessibilityNodeInfo node(String value) {
        for (int attempt=0;attempt<30;attempt++) { AccessibilityNodeInfo found=find(getUiAutomation().getRootInActiveWindow(),value); if(found!=null)return found; SystemClock.sleep(100); }
        throw new AssertionError("Missing UI: "+value);
    }
    private AccessibilityNodeInfo reveal(String value) { AccessibilityNodeInfo target=node(value);target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());settle();return node(value); }
    private void click(String value) { AccessibilityNodeInfo target=reveal(value);while(target!=null&&!target.isClickable())target=target.getParent();require(target!=null&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Cannot click "+value);settle(); }
    private void visible(String value) { AccessibilityNodeInfo target=reveal(value);Rect bounds=new Rect();target.getBoundsInScreen(bounds);require(target.isVisibleToUser()&&!bounds.isEmpty(),"Control not visible: "+value); }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo root,String label) { if(root==null)return null;if(root.isEditable()&&label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo found=editable(root.getChild(i),label);if(found!=null)return found;}return null; }
    private void setText(String label,String text) { Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);AccessibilityNodeInfo edit=editable(getUiAutomation().getRootInActiveWindow(),label);require(edit!=null&&edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Cannot enter "+label);settle(); }
    private void screenshot(String name) throws Exception { Bitmap bitmap=getUiAutomation().takeScreenshot();require(bitmap!=null,"Screenshot unavailable");File directory=new File(getTargetContext().getFilesDir(),"ui-verification");require(directory.isDirectory()||directory.mkdirs(),"Cannot save screenshots");try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle(); }
    private void verifyKeyboardButton(){
        int keyboardTop=-1;for(int attempt=0;attempt<30&&keyboardTop<0;attempt++){for(android.view.accessibility.AccessibilityWindowInfo window:getUiAutomation().getWindows())if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect b=new Rect();window.getBoundsInScreen(b);if(!b.isEmpty())keyboardTop=b.top;}if(keyboardTop<0)SystemClock.sleep(100);}
        require(keyboardTop>=0,"Soft keyboard did not appear after text tab");Rect action=new Rect();node("翻译文字  →").getBoundsInScreen(action);require(action.bottom<=keyboardTop,"Translate button is hidden by keyboard");
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
