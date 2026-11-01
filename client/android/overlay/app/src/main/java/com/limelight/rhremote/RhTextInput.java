package com.limelight.rhremote;

import android.graphics.Typeface;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Keyboard hints for exact technical values; parsing and validation stay in the existing actions. */
final class RhTextInput {
    private RhTextInput(){}
    static void address(EditText editor){configure(editor,InputType.TYPE_TEXT_VARIATION_URI);}
    static void filter(EditText editor){configure(editor,InputType.TYPE_TEXT_VARIATION_FILTER);}
    static void username(EditText editor){configure(editor,InputType.TYPE_TEXT_VARIATION_NORMAL);}
    private static void configure(EditText editor,int variation){
        Typeface font=editor.getTypeface();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        // URI/filter prevent framework spell checking. Leave IME composition candidates available.
        editor.setInputType(InputType.TYPE_CLASS_TEXT|variation);editor.setSingleLine(true);editor.setTypeface(font);
        editor.setImeOptions(editor.getImeOptions()|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if(start>=0&&end>=0)editor.setSelection(Math.min(start,editor.length()),Math.min(end,editor.length()));
    }
}
