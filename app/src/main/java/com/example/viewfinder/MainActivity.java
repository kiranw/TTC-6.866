// ViewFinder - a simple app to:
//	  (i) read camera & show preview image on screen,
//	 (ii) compute some simple statistics from preview image and
//	(iii) superimpose results on screen in text and graphic form
// Based originally on http://web.stanford.edu/class/ee368/Android/ViewfinderEE368/

package com.example.viewfinder;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;

import java.io.IOException;
import java.util.List;

// ----------------------------------------------------------------------

public class MainActivity extends Activity
{
	String TAG = "ViewFinder";    // tag for logcat output
    String asterisks = " *******************************************"; // for noticeable marker in log
    protected static int mCam = 0;      // the number of the camera to use (0 => rear facing)
    protected static Camera mCamera = null;
    int nPixels = 480 * 640;            // approx number of pixels desired in preview
    protected static int mCameraHeight;   // preview height (determined later)
    protected static int mCameraWidth;    // preview width
    protected static Preview mPreview;
    protected static DrawOnTop mDrawOnTop;
	protected static LayoutParams mLayoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	private static boolean DBG=true;
	
    static boolean bDisplayInfoFlag = true;	// show info about display  in log file
    static boolean nCameraInfoFlag = true;	// show info about cameras in log file

    @Override
	protected void onCreate (Bundle savedInstanceState)
	{
        super.onCreate(savedInstanceState);
        if (DBG) Log.v(TAG, "onCreate" + asterisks);
        if (!checkCameraHardware(this)) {    // (need "context" as argument here)
            Log.e(TAG, "Device does not have a camera! Exiting"); // tablet perhaps?
            System.exit(0);    // finish() 
        }
        // go full screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // and hide the window title.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // optional dump of useful info into the log
		if (bDisplayInfoFlag) ExtraInfo.showDisplayInfo(this); // show some info about display
		if (nCameraInfoFlag) ExtraInfo.showCameraInfoAll(); // show some info about all cameras
    }

    // Because the CameraDevice object is not a shared resource,
    // it's very important to release it when the activity is paused.

    @Override
	protected void onPause ()
	{
        super.onPause();
        if (DBG) Log.v(TAG, "onPause" + asterisks);
        releaseCamera(mCam, true);    // release camera here
    }

    // which means the CameraDevice has to be (re-)opened when the activity is (re-)started

    @Override
	protected void onResume ()
	{
        super.onResume();
        if (DBG) Log.v(TAG, "onResume" + asterisks);
        openCamera(mCam);    // (re-)open camera here
        getPreviewSize(mCamera, nPixels);    // pick an available preview size

        // Create our DrawOnTop view.
        mDrawOnTop = new DrawOnTop(this);
        // Create our Preview view
        mPreview = new Preview(this, mDrawOnTop);
        // and set preview as the content of our activity.
        setContentView(mPreview);
        // and add overlay to content of our activity.
        addContentView(mDrawOnTop, mLayoutParams);
    }

    @Override
	protected void onDestroy ()
	{
        super.onDestroy();
        if (DBG) Log.v(TAG, "onDestroy" + asterisks);
        releaseCamera(mCam, true);    // if it hasn't been released yet...
    }

    //////////////////////////////////////////////////////////////////////////////

    // Check if this device actually has a camera!
	public static boolean checkCameraHardware (Context context)
	{
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

	protected static void openCamera (int nCam)
	{
        String TAG = "openCamera";
        if (mCamera == null) {
            try {
                if (DBG) Log.i(TAG, "Opening camera " + nCam);
                mCamera = Camera.open(nCam);
            } catch (Exception e) {
                Log.e(TAG, "ERROR: camera open exception " + e);
                System.exit(0); // should not happen
            }
        }
		else Log.e(TAG, "Camera already open");
    }

	protected static void releaseCamera (int nCam, boolean previewFlag)
	{
		String TAG = "releaseCamera";
		if (mCamera != null) {
			if (DBG) Log.i(TAG, "Releasing camera " + nCam);
			if (previewFlag) {    // if we have been getting previews from this camera
				mCamera.setPreviewCallback(null);
				mCamera.stopPreview();
			}
			mCamera.release();
			mCamera = null;
		}
		else Log.e(TAG, "No camera to release");
	}

	private static void getPreviewSize (Camera mCamera, int nPixels)
	{ //	pick one of the available preview size
        String TAG = "getPreviewSize";
        Camera.Parameters params = mCamera.getParameters();
        List<Camera.Size> cSizes = params.getSupportedPictureSizes();
        int dPixels, dMinPixels = -1;
        if (DBG) Log.i(TAG, "Looking for about " + nPixels + " pixels");
        for (Camera.Size cSize : cSizes) {    // step through available camera preview image sizes
            if (DBG) Log.i(TAG, "Size " + cSize.height + " x " + cSize.width); // debug log output
//			use desired pixel count as a guide to selection
            dPixels = Math.abs(cSize.height * cSize.width - nPixels);
            if (dMinPixels < 0 || dPixels < dMinPixels) {
                mCameraHeight = cSize.height;
                mCameraWidth = cSize.width;
                dMinPixels = dPixels;
            }
        }
        if (DBG) Log.i(TAG, "Nearest fit available preview image size: " + mCameraHeight + " x " + mCameraWidth);
    }

//------- nested class DrawOnTop ---------------------------------------------------------------

	class DrawOnTop extends View
	{
        int counter;
        Bitmap mBitmap;
        byte[] mYUVData;
        int[] mRGBData;
        float[] brightness;
        float[] prevBrightness;
        float[] deltaBrightness;
        float[][] E;
        float[][] prevE;
        float[][] E_x;
        float[][] E_y;
        float[][] E_t;
        float[][] prevE_x;
        float[][] prevE_y;
        float[][] prevE_t;
        int mImageWidth, mImageHeight;
        int[] mRedHistogram;
        int[] mGreenHistogram;
        int[] mBlueHistogram;
		Paint mPaintBlack;
		Paint mPaintYellow;
		Paint mPaintRed;
		Paint mPaintGreen;
		Paint mPaintBlue;
		int mTextsize = 90;		// controls size of text on screen
		int mLeading;			// spacing between text lines
        RectF barRect = new RectF();	// used in drawing histogram
		double redMean, greenMean, blueMean;
        float brightnessMean;	// computed results
		double redStdDev, greenStdDev, blueStdDev;
        int totalBrightness;
        float prevBrightnessMean;
		String TAG = "DrawOnTop";       // for logcat output

		public DrawOnTop (Context context)
		{ // constructor
            super(context);

            counter = 0;
            brightness = new float[480*640];
            prevBrightness = new float[480*640];

            prevE_x = new float[480][640];
            prevE_y = new float[480][640];
            prevE_t = new float[480][640];

            E = new float[480][640];
            prevE = new float[480][640];
            E_x = new float[480][640];
            E_y = new float[480][640];
            E_t = new float[480][640];

            mPaintBlack = makePaint(Color.BLACK);
            mPaintYellow = makePaint(Color.YELLOW);
            mPaintRed = makePaint(Color.RED);
            mPaintGreen = makePaint(Color.GREEN);
            mPaintBlue = makePaint(Color.BLUE);

            mBitmap = null;	// will be set up later in Preview - PreviewCallback
            mYUVData = null;
            mRGBData = null;
            deltaBrightness = null;

            mRedHistogram = new int[256];
            mGreenHistogram = new int[256];
            mBlueHistogram = new int[256];
            barRect = new RectF();    // moved here to reduce GC
			if (DBG) Log.i(TAG, "DrawOnTop textsize " + mTextsize);
			mLeading = mTextsize * 6 / 5;    // adjust line spacing
			if (DBG) Log.i(TAG, "DrawOnTop Leading " + mLeading);

        }

		Paint makePaint (int color)
		{
            Paint mPaint = new Paint();
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            mPaint.setTextSize(mTextsize);
            mPaint.setTypeface(Typeface.MONOSPACE);
            return mPaint;
        }

		// Called when preview is drawn on screen
		// Compute some statistics and draw text and histograms on screen

        @Override
        protected void onDraw (Canvas canvas) 
        {
			String TAG="onDraw";
			if (mBitmap == null) {	// sanity check
				Log.w(TAG, "mBitMap is null");
				super.onDraw(canvas);
				return;	// because not yet set up
			}

            if (counter > 1) {
                prevBrightness = new float[480*640];
                System.arraycopy( brightness, 0, prevBrightness, 0, 480*640);
            }

            counter += 1;
            Log.w(TAG, "counter: " + String.format("%4d", (int) counter));

			// Convert image from YUV to RGB format:
			//decodeYUV420SP(mRGBData, brightness, mYUVData, mImageWidth, mImageHeight);
            decodeYUV420SPGrayscale(mRGBData, brightness, mYUVData, mImageWidth, mImageHeight);

			// Now do some image processing here:

			// Calculate histograms
//			calculateIntensityHistograms(mRGBData, mRedHistogram, mGreenHistogram, mBlueHistogram,
//										 mImageWidth, mImageHeight);

			// calculate means and standard deviations
//			calculateMeanAndStDev(mRedHistogram, mGreenHistogram, mBlueHistogram, mImageWidth * mImageHeight, brightness);

            // calculate E, prevE, E_x, E_y, E_t
            for (int i=0; i < 480; i++){
                for (int j=0; j < 640; j++){
                    E[i][j] = brightness[i*640 + j];
                    prevE[i][j] = prevBrightness[i*640 + j];
                }
            }

            // calculate differences in brightness
            for (int a=0; a < 480; a++) {
                for (int b = 0; b < 640; b++) {
                    prevE_x[a][b] = (counter > 1) ? E_x[a][b] : 0;
                    prevE_y[a][b] = (counter > 1) ? E_y[a][b] : 0;
                    prevE_t[a][b] = (counter > 1) ? E_t[a][b] : 0;
                    E_x[a][b] = (a == 479) ? 0 : E[a][b] - E[a + 1][b];
                    E_y[a][b] = (b == 639) ? 0 : E[a][b] - E[a][b + 1];
                    E_t[a][b] = E[a][b] - prevE[a][b];
                }
            }


            // subsample
            float[][] subE_x = new float[120][160];
            float[][] subE_y = new float[120][160];
            float[][] subE_t = new float[120][160];

            int newX = -1;
            int newY;
            float avgE_t = 0;
            float maxE_t = 0;
            for (int a=0; a < 480; a=a+4) {
                newY = -1;
                newX++;
                for (int b = 0; b < 640; b = b + 4) {
                    newY++;
                    int xEnd = Math.min(480, a + 4);
                    int yEnd = Math.min(640, b + 4);
                    float xAvg = 0;
                    float yAvg = 0;
                    float tAvg = 0;
                    for (int xStart = a; xStart < xEnd; xStart++) {
                        for (int yStart = b; yStart < yEnd; yStart++) {
                            xAvg += E_x[xStart][yStart] + prevE_x[xStart][yStart];
                            yAvg += E_y[xStart][yStart] + prevE_y[xStart][yStart];
                            tAvg += E_t[xStart][yStart] + prevE_t[xStart][yStart];
                        }
                    }
                    xAvg /= 2*((xEnd - a) * (yEnd - b));
                    yAvg /= 2*((xEnd - a) * (yEnd - b));
                    tAvg /= 2*((xEnd - a) * (yEnd - b));
                    subE_x[newX][newY] = xAvg;
                    subE_y[newX][newY] = yAvg;
                    subE_t[newX][newY] = tAvg;
                    avgE_t += tAvg;
                    maxE_t = Math.max(Math.abs(tAvg), maxE_t);
                }
            }

            avgE_t /=(120*160);
            Log.w("Average E_t", String.valueOf(avgE_t));
            Log.w("Max E_t", String.valueOf(maxE_t));
            float E_threshold = Math.abs(avgE_t);

            float ttc_sum = 0;
            float sum_g_squared = 0;
            float sum_ex_ey = 0;
            float sum_g_ex = 0;
            float sum_g_ey = 0;
            float sum_g_et = 0;
            float sum_ex_squared = 0;
            float sum_ey_squared = 0;
            float sum_ey_et = 0;
            float sum_ex_et = 0;
            float sum_g_squared_x_y = 0;
            float sum_g_x_et = 0;
            float sum_g_y_et = 0;
            float sum_g_squared_x = 0;
            float sum_g_squared_y = 0;
            float sum_g_squared_x_squared = 0;
            float sum_g_squared_y_squared = 0;

            for (int a=0; a < 120; a++){
                for (int b=0; b < 160; b++){
                    if (Math.abs(subE_y[a][b]) > E_threshold) {
                        float G = (a / 1) * subE_x[a][b] + (b / 1) * subE_y[a][b];
                        ttc_sum += G;
                        sum_g_squared += G * G;
                        sum_ex_ey += subE_x[a][b] * subE_y[a][b];
                        sum_g_ex += G * subE_x[a][b];
                        sum_g_ey += G * subE_y[a][b];
                        sum_g_et += G * subE_t[a][b];
                        sum_ex_squared += subE_x[a][b] * subE_x[a][b];
                        sum_ey_squared += subE_y[a][b] * subE_y[a][b];
                        sum_ey_et += subE_y[a][b] * subE_t[a][b];
                        sum_ex_et += subE_x[a][b] * subE_t[a][b];
                        sum_g_squared_x_y += G * G * (a / 1) * (b / 1);
                        sum_g_x_et += G * (a / 1) * subE_t[a][b];
                        sum_g_y_et += G * (b / 1) * subE_t[a][b];
                        sum_g_squared_x += G * G * (a / 1);
                        sum_g_squared_y += G * G * (b / 1);
                        sum_g_squared_x_squared += G * G * (a / 1) * (a / 1);
                        sum_g_squared_y_squared += G * G * (b / 1) * (b / 1);
                    }
                }
            }

            //method 1
            float ttc = - sum_g_squared / sum_g_et;

            //method 2
            float n1_c2 = (-sum_g_et*sum_ex_ey + sum_ey_et*sum_g_ex)*(sum_ex_squared*sum_ey_squared-(sum_ex_ey*sum_ex_ey));
            float n2_c2 = (-sum_ey_et * sum_ex_squared + sum_ex_et * sum_ex_ey)*(sum_g_ey*sum_ex_ey - sum_ey_squared*sum_g_ex);
            float d1_c2 = (sum_g_squared*sum_ex_ey - sum_g_ey * sum_g_ex)*(sum_ex_squared*sum_ey_squared-(sum_ex_ey*sum_ex_ey));
            float d2_c2 = (sum_g_ey*sum_ex_squared - sum_g_ex*sum_ex_ey)*(sum_g_ey*sum_ex_ey - sum_ey_squared*sum_g_ex);
            float c2 = (n1_c2-n2_c2)/(d1_c2-d2_c2);
            float ttc2 = 1/c2;

            float n_b2_1 = -sum_ey_et*sum_ex_squared + sum_ex_et*sum_ex_ey - c2*(sum_g_ey*sum_ex_squared-sum_g_ex*sum_ex_ey);
            float d_b2_1 = (sum_ex_squared*sum_ey_squared-sum_ex_ey*sum_ex_ey);

            float n_b2_2 = -sum_g_et*sum_ex_ey+sum_ey_et*sum_g_ex-c2*(sum_g_squared*sum_ex_ey-sum_g_ey*sum_g_ex);
            float d_b2_2 = sum_g_ey*sum_ex_ey-sum_ey_squared*sum_g_ex;

            //these are equal (sanity check)
            float b2_1 = n_b2_1/d_b2_1;
            float b2_2 = n_b2_2/d_b2_2;
            //Log.w("b2_1: ", String.valueOf(b2_1));
            //Log.w("b2_2: ", String.valueOf(b2_2));

            //these are equal (sanity check)
            float a2_1 = (-sum_ex_et - b2_1*sum_ex_ey - c2*sum_g_ex)/sum_ex_squared;
            float a2_2 = (-sum_ey_et - b2_1*sum_ey_squared - c2*sum_g_ey)/sum_ex_ey;
            float a2_3 = (-sum_g_et - b2_1*sum_g_ey - c2*sum_g_squared)/sum_g_ex;
            //Log.w("a2_1: ", String.valueOf(a2_1));
            //Log.w("a2_2: ", String.valueOf(a2_2));
            //Log.w("a2_3: ", String.valueOf(a2_3));

            //FOE
            float x_0 = -a2_1 / c2;
            float y_0 = -b2_1 / c2;


            //method 3
            double numerator1_1 = (-sum_g_et*sum_g_squared_x_y + sum_g_y_et*sum_g_squared_x)*(sum_g_squared_y_squared*sum_g_squared_x_squared-sum_g_squared_x_y*sum_g_squared_x_y);
            double numerator1_2 = (-sum_g_y_et*sum_g_squared_x_squared+sum_g_x_et*sum_g_squared_x_y)*(sum_g_squared_y*sum_g_squared_x_y-sum_g_squared_y_squared*sum_g_squared_x_squared);
            double denom1_1 = (sum_g_squared*sum_g_squared_x_y-sum_g_squared_y*sum_g_squared_x)*(sum_g_squared_y_squared*sum_g_squared_x_squared - sum_g_squared_x_y*sum_g_squared_x_y);
            double denom1_2 = (sum_g_squared_y*sum_g_squared_x_squared-sum_g_squared_x*sum_g_squared_x_y)*(sum_g_squared_y*sum_g_squared_x_y-sum_g_squared_y_squared*sum_g_squared_x);
            double c3 = (numerator1_1-numerator1_2)/(denom1_1-denom1_2);
            double ttc3 = 1/c3;

            float sum = 0;
            for (int i=0; i < nPixels; i++) {
                sum = sum + prevBrightness[i];
            }
            prevBrightnessMean = sum / nPixels;

			// Finally, use the results to draw things on top of screen:
			int canvasHeight = canvas.getHeight();
			int canvasWidth = canvas.getWidth();
			int newImageWidth = canvasWidth - 200;
			int marginWidth = (canvasWidth - newImageWidth) / 2;

//            String imageBrightnessStr = "Brightness: " + String.format("%s", (float) brightnessMean);
//            drawTextOnBlack(canvas, imageBrightnessStr, marginWidth+10, 1 * mLeading, mPaintYellow);
//            String imageBrightnessDeltaStr = "PrevBrightnessMean: " + String.format("%s", (float) prevBrightnessMean);
//            drawTextOnBlack(canvas, imageBrightnessDeltaStr, marginWidth+10, 2 * mLeading, mPaintYellow);
            drawTextOnBlack(canvas, "TTC1: " + String.format("%s", ttc), marginWidth+10, 1 * mLeading, mPaintGreen);
            drawTextOnBlack(canvas, "TTC2: " + String.format("%s", ttc2), marginWidth+10, 2 * mLeading, mPaintGreen);
            drawTextOnBlack(canvas, "TTC3: " + String.format("%s", ttc3), marginWidth+10, 3 * mLeading, mPaintGreen);
            drawTextOnBlack(canvas, "FOE: (" + String.format("%s", x_0) + ", " + String.format("%s", y_0) + ")", marginWidth+10, 4 * mLeading, mPaintRed);


			float barWidth = ((float) newImageWidth) / 25;
            int left1 = (int) (newImageWidth - 3*marginWidth - 3*barWidth);
            int left2 = (int) (newImageWidth - 2*marginWidth - 2*barWidth);
            int left3 = (int) (newImageWidth - marginWidth - barWidth);
            drawTTCBar(canvas, mPaintRed, ttc, canvasHeight, left1, barWidth);
            drawTTCBar(canvas, mPaintYellow, ttc2/10, canvasHeight, left2, barWidth);
            drawTTCBar(canvas, mPaintGreen, (float) ttc3*1000, canvasHeight, left3, barWidth);
            drawFOE(canvas, mPaintRed, x_0, y_0, canvasHeight, newImageWidth);
            super.onDraw(canvas);

		} // end onDraw method

		public void decodeYUV420SP (int[] rgb, float[] brightness, byte[] yuv420sp, int width, int height)
		{ // convert image in YUV420SP format to RGB format
            final int frameSize = width * height;

            for (int j = 0, pix = 0; j < height; j++) {
				int uvp = frameSize + (j >> 1) * width;	// index to start of u and v data for this row
				int u = 0, v = 0;
                for (int i = 0; i < width; i++, pix++) {
                    int y = (0xFF & ((int) yuv420sp[pix])) - 16;
                    if (y < 0) y = 0;
                    if ((i & 1) == 0) { // even row & column (u & v are at quarter resolution of y)
                        v = (0xFF & yuv420sp[uvp++]) - 128;
                        u = (0xFF & yuv420sp[uvp++]) - 128;
                    }

                    int y1192 = 1192 * y;
                    int r = (y1192 + 1634 * v);
                    int g = (y1192 - 833 * v - 400 * u);
                    int b = (y1192 + 2066 * u);

                    
                    if (r < 0) r = 0;
                    else if (r > 0x3FFFF) r = 0x3FFFF;
                    if (g < 0) g = 0;
                    else if (g > 0x3FFFF) g = 0x3FFFF;
                    if (b < 0) b = 0;
                    else if (b > 0x3FFFF) b = 0x3FFFF;

                    rgb[pix] = 0xFF000000 | ((r << 6) & 0xFF0000) | ((g >> 2) & 0xFF00) | ((b >> 10) & 0xFF);
                    brightness[pix] = (r * 0.2126f + g * 0.7152f + b * 0.0722f) / 255;
                }
            }
        }

		public void decodeYUV420SPGrayscale (int[] rgb, float[] brightness, byte[] yuv420sp, int width, int height)
		{ // extract grey RGB format image --- not used currently
			final int frameSize = width * height;

			// This is much simpler since we can ignore the u and v components
            for (int pix = 0; pix < frameSize; pix++) {	
                int y = (0xFF & ((int) yuv420sp[pix])) - 16;
                if (y < 0) y = 0;
                if (y > 0xFF) y = 0xFF;
                rgb[pix] = 0xFF000000 | (y << 16) | (y << 8) | y;
                brightness[pix] = (float) y/255;
            } 
        }

        // This is where we finally actually do some "image processing"!
		public void calculateIntensityHistograms(int[] rgb, int[] redHistogram, int[] greenHistogram, int[] blueHistogram, int width, int height)
		{
            final int dpix = 1;
            int red, green, blue, bin, pixVal;
            for (bin = 0; bin < 256; bin++) { // reset the histograms
                redHistogram[bin] = 0;		
                greenHistogram[bin] = 0;
                blueHistogram[bin] = 0;
            }
            for (int pix = 0; pix < width * height; pix += dpix) {
                pixVal = rgb[pix];
                blue = pixVal & 0xFF;
                blueHistogram[blue]++;
                pixVal = pixVal >> 8;
                green = pixVal & 0xFF;
                greenHistogram[green]++;
                pixVal = pixVal >> 8;
                red = pixVal & 0xFF;
                redHistogram[red]++;
            }
		}
	
		private void calculateMeanAndStDev (int mRedHistogram[], int mGreenHistogram[], int mBlueHistogram[], int nPixels, float[] brightness)
		{
			// Calculate first and second moments (zeroth moment equals nPixels)
			double red1stMoment = 0, green1stMoment = 0, blue1stMoment = 0;
			double red2ndMoment = 0, green2ndMoment = 0, blue2ndMoment = 0;
			double binsquared = 0;
			for (int bin = 0; bin < 256; bin++) {
				binsquared += (bin << 1) - 1;	// n^2 - (n-1)^2 = 2*n - 1
				red1stMoment   += mRedHistogram[bin]   * bin;
				green1stMoment += mGreenHistogram[bin] * bin;
				blue1stMoment  += mBlueHistogram[bin]  * bin;
				red2ndMoment   += mRedHistogram[bin]   * binsquared;
				green2ndMoment += mGreenHistogram[bin] * binsquared;
				blue2ndMoment  += mBlueHistogram[bin]  * binsquared;

			} // bin

            float sum = 0;
            for (int i=0; i < nPixels; i++) {
                sum = sum + brightness[i];
            }
            brightnessMean = sum / nPixels;

            redMean   = red1stMoment   / nPixels;
			greenMean = green1stMoment / nPixels;
			blueMean  = blue1stMoment  / nPixels;

			redStdDev   = Math.sqrt(red2ndMoment   / nPixels - redMean * redMean);
			greenStdDev = Math.sqrt(green2ndMoment / nPixels - greenMean * greenMean);
			blueStdDev  = Math.sqrt(blue2ndMoment  / nPixels - blueMean * blueMean);
		}

		private void drawTextOnBlack (Canvas canvas, String str, int rPos, int cPos, Paint mPaint)
		{ // make text stand out from background by providing thin black border
            Typeface font = Typeface.createFromAsset(getAssets(), "fonts/sqmarket-regular.ttf");
            mPaint.setTypeface(font);
            mPaint.setTextSize(80f);
			canvas.drawText(str, rPos, cPos, mPaint);
		}

		private void drawHistogram (Canvas canvas, Paint mPaint,
									int mHistogram[], int nPixels,
									int mBottom, int marginWidth, float barWidth)
		{
			float barMaxHeight = 3000; // controls vertical scale of histogram
			float barMarginHeight = 2;

			barRect.bottom = mBottom;
			barRect.left = marginWidth;
			barRect.right = barRect.left + barWidth;
			for (int bin = 0; bin < 256; bin++) {
				float prob = (float) mHistogram[bin] / (float) nPixels;
				barRect.top = barRect.bottom - Math.min(80, prob * barMaxHeight) - barMarginHeight;
				canvas.drawRect(barRect, mPaintBlack);
				barRect.top += barMarginHeight;
				canvas.drawRect(barRect, mPaint);
				barRect.left += barWidth;
				barRect.right += barWidth;
			}
		}

        void drawTTCBar (Canvas canvas, Paint mPaint, float ttc, int mBottom, int barLeft, float barWidth)
        {
            float barMaxHeight = 10; // controls vertical scale of histogram
            float barMarginHeight = 2;

            if (ttc < 0) {
                ttc = -ttc;
            }

            barRect.bottom = mBottom;
            barRect.left = barLeft;
            barRect.right = barRect.left + barWidth;
            barRect.top = barRect.bottom - Math.min(600, ttc * barMaxHeight) - barMarginHeight;
            canvas.drawRect(barRect, mPaint);
        }

        void drawFOE(Canvas canvas, Paint mPaint, float x_0, float y_0, int canvasHeight, int newImageWidth) {
            if (x_0 > 0 && y_0 > 0){
                barRect.bottom = y_0/640 * canvasHeight;
                barRect.top = barRect.bottom - 30;
                barRect.left = x_0/480 * newImageWidth;
                barRect.right = barRect.left + 30;
                canvas.drawRect(barRect, mPaint);
            }

        }
	}


// -------- nested class Preview --------------------------------------------------------------

    class Preview extends SurfaceView implements SurfaceHolder.Callback
    {	// deal with preview that will be shown on screen
        SurfaceHolder mHolder;
        DrawOnTop mDrawOnTop;
        boolean mFinished;
        String TAG="PreView";	// tag for LogCat

        public Preview (Context context, DrawOnTop drawOnTop)
        { // constructor
            super(context);

            mDrawOnTop = drawOnTop;
            mFinished = false;

            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            mHolder = getHolder();
            mHolder.addCallback(this);
            //  Following is deprecated setting, but required on Android versions prior to 3.0:
            //  mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); 
        }

        public void surfaceCreated (SurfaceHolder holder)
		{
			String TAG="surfaceCreated";
            PreviewCallback mPreviewCallback;
            if (mCamera == null) {	// sanity check
                Log.e(TAG, "ERROR: camera not open");
                System.exit(0);
            }
            Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
			android.hardware.Camera.getCameraInfo(mCam, info);
			// show some potentially useful information in log file
            switch (info.facing)  {	// see which camera we are using
                case Camera.CameraInfo.CAMERA_FACING_BACK:
                    Log.i(TAG, "Camera "+mCam+" facing back");
                    break;
                case Camera.CameraInfo.CAMERA_FACING_FRONT:
                    Log.i(TAG, "Camera "+mCam+" facing front");
                    break;
            }
			if (DBG) Log.i(TAG, "Camera "+mCam+" orientation "+info.orientation);

            mPreviewCallback = new PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera camera) { // callback
                    String TAG = "onPreviewFrame";
                    if ((mDrawOnTop == null) || mFinished) return;
                    if (mDrawOnTop.mBitmap == null)  // need to initialize the drawOnTop companion?
                        mDrawOnTop.prevBrightness = mDrawOnTop.brightness;

						setupArrays(data, camera);
                    // Pass YUV image data to draw-on-top companion
                    System.arraycopy(data, 0, mDrawOnTop.mYUVData, 0, data.length);
                    mDrawOnTop.invalidate();
                }
            };

            try {
                mCamera.setPreviewDisplay(holder);
                // Preview callback will be used whenever new viewfinder frame is available
                mCamera.setPreviewCallback(mPreviewCallback);
            }
            catch (IOException e) {
                Log.e(TAG, "ERROR: surfaceCreated - IOException " + e);
                mCamera.release();
                mCamera = null;
            }
        }

        public void surfaceDestroyed (SurfaceHolder holder)
		{
			String TAG="surfaceDestroyed";
            // Surface will be destroyed when we return, so stop the preview.
            mFinished = true;
            if (mCamera != null) {	// not expected
                Log.e(TAG, "ERROR: camera still open");
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
            }
        }

        public void surfaceChanged (SurfaceHolder holder, int format, int w, int h)
        {
			String TAG="surfaceChanged";
            //	Now that the size is known, set up the camera parameters and begin the preview.
            if (mCamera == null) {	// sanity check
                Log.e(TAG, "ERROR: camera not open");
                System.exit(0);
            }
			if (DBG) Log.v(TAG, "Given parameters h " + h + " w " + w);
			if (DBG) Log.v(TAG, "What we are asking for h " + mCameraHeight + " w " + mCameraWidth);
			if (h != mCameraHeight || w != mCameraWidth)
				Log.w(TAG, "Mismatch in image size "+" "+h+" x "+w+" vs "+mCameraHeight+" x "+mCameraWidth);
			// this will be sorted out with a setParamaters() on mCamera
			Camera.Parameters parameters = mCamera.getParameters();
			parameters.setPreviewSize(mCameraWidth, mCameraHeight);
			// check whether following is within PreviewFpsRange ?
            parameters.setPreviewFrameRate(15);	// deprecated
			// parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO); 
            try {
                mCamera.setParameters(parameters);
            } catch (Exception e) {
                Log.e(TAG, "ERROR: setParameters exception " + e);
                System.exit(0);
            }
            mCamera.startPreview();
        }

		private void setupArrays (byte[] data, Camera camera)
		{
			String TAG="setupArrays";
			if (DBG) Log.i(TAG, "Setting up arrays");
//            mDrawOnTop.counter += 1;
//            mDrawOnTop.prevBrightness = mDrawOnTop.brightness;
//            mDrawOnTop.brightness = new float[mDrawOnTop.mImageWidth * mDrawOnTop.mImageHeight];

			Camera.Parameters params = camera.getParameters();
			mDrawOnTop.mImageHeight = params.getPreviewSize().height;
			mDrawOnTop.mImageWidth = params.getPreviewSize().width;
			if (DBG) Log.i(TAG, "height " + mDrawOnTop.mImageHeight + " width " + mDrawOnTop.mImageWidth);
			mDrawOnTop.mBitmap = Bitmap.createBitmap(mDrawOnTop.mImageWidth,
				mDrawOnTop.mImageHeight, Bitmap.Config.RGB_565);
			mDrawOnTop.mRGBData = new int[mDrawOnTop.mImageWidth * mDrawOnTop.mImageHeight];
            mDrawOnTop.deltaBrightness = new float[mDrawOnTop.mImageWidth * mDrawOnTop.mImageHeight];
			if (DBG) Log.i(TAG, "data length " + data.length); // should be width*height*3/2 for YUV format
			mDrawOnTop.mYUVData = new byte[data.length];
			int dataLengthExpected = mDrawOnTop.mImageWidth * mDrawOnTop.mImageHeight * 3 / 2;
			if (data.length != dataLengthExpected)
				Log.e(TAG, "ERROR: data length mismatch "+data.length+" vs "+dataLengthExpected);
		}

    }
}

// NOTE: the "Camera" class is deprecated as of API 21, but very few
// devices support the new Camera2 API, and even fewer support it fully
// and correctly (as of summer 2015: Motorola Nexus 5 & 6 and just possibly Samsung S6)
// So, for now, we use the "old" Camera class here.

