package net.phase.wallet;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Random;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.RemoteViews;

class WidgetHandler extends Handler
{
	private Context context;

	public WidgetHandler( Context context )
	{
		this.context = context;
	}
	
	public void handleMessage( Message msg )
	{
		switch ( msg.what )
		{
			case BalanceRetriever.MESSAGE_FINISHED:
				switch (msg.arg1)
				{
					case BalanceRetriever.MESSAGE_STATUS_SUCCESS:
						// send an update to the widget
				        Intent intent = new Intent( WalletWidgetProvider.UPDATE_WIDGET );
				        intent.setClassName( "net.phase.wallet", "net.phase.wallet.WalletWidgetProvider" );
				        context.sendBroadcast( intent );
				        
						break;
				}
				break;
		}
	}

}
public class WalletWidgetProvider extends AppWidgetProvider
{
	public static final String UPDATE_WIDGET = "net.phase.wallet.UPDATE_WIDGET";

	public void onEnabled( Context context )
	{
	}

	@Override
	public void onReceive(Context context, Intent intent) 
	{
	    String action = intent.getAction();
	    
	    if ( action.equals( UPDATE_WIDGET ) )
	    {
	    	try
	    	{
	    		Thread.sleep( 2000 );
	    	}
	    	catch ( InterruptedException e )
	    	{
	    		
	    	}
	        updateBalance( context );    
	    }
	    else
	    {
	    	super.onReceive(context, intent);
	    }
	}

	private void updateBalance( Context context )
	{
    	AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, WalletWidgetProvider.class));

        updateBalance( context, appWidgetManager, appWidgetIds );
	}
	
	private void updateBalance( Context context, AppWidgetManager appWidgetManager, int [] appWidgetIds )
	{
        Wallet [] wallets = null;
    	long balance = 0;

		try
		{
			wallets = Wallet.getStoredWallets(context, null );
		}
		catch ( IOException e )
		{
		}

		if ( wallets != null )
    	{
	    	for ( Wallet w : wallets )
	    	{
	    		balance += w.balance;
	    	}
    	}
		
    	for (int id : appWidgetIds)
    	{
	    	RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);

	    	DecimalFormat df = new DecimalFormat("0.00");
	    	views.setTextViewText( R.id.widgettext, "BTC\n" + df.format( balance / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
	    	appWidgetManager.updateAppWidget( id, views );
    	}
	}

	// called every 30 mins by the appwidget manager
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds)
    {
    	/*** this code isn't ready yet
        Wallet [] wallets = null;

		try
		{
			wallets = Wallet.getStoredWallets(context, null );
		}
		catch ( IOException e )
		{
		}

		if ( wallets != null )
    	{
	    	for ( Wallet w : wallets )
	    	{
	    		WidgetHandler handler = new WidgetHandler( context );
	    		BalanceRetriever br = new BalanceRetriever( handler, w, true );
	    		Thread t = new Thread( br );
	    		t.start();
	    	}
    	}
    	*/
    	// set up the click action
    	for (int id : appWidgetIds)
    	{
	    	RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);

	    	Intent intent = new Intent(context, WalletActivity.class);

	    	PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
	    	views.setOnClickPendingIntent( R.id.widgetlayout, pendingIntent );

	    	appWidgetManager.updateAppWidget( id, views );
    	}
    	
    	updateBalance( context, appWidgetManager, appWidgetIds );
    }
}