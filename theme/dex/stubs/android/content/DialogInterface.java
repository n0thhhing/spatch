package android.content;

public interface DialogInterface {
    int BUTTON_POSITIVE = -1;
    int BUTTON_NEGATIVE = -2;

    void dismiss();

    interface OnClickListener {
        void onClick(DialogInterface d, int which);
    }
}
