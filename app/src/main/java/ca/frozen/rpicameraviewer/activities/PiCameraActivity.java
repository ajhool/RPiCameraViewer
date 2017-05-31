// Copyright © 2016 Shawn Baker using the MIT License.
package ca.frozen.rpicameraviewer.activities;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

import ca.frozen.rpicameraviewer.App;
import ca.frozen.rpicameraviewer.classes.NetworkCameraSource;
import ca.frozen.rpicameraviewer.classes.Utils;
import ca.frozen.rpicameraviewer.R;

public class PiCameraActivity extends AppCompatActivity
{
	// public constants
	public final static String CAMERA = "networkCameraSource";

	// local constants
	private final static String TAG = "PiCameraActivity";

	// instance variables
	private NetworkCameraSource networkCameraSource;
	private EditText nameEdit;
	private SourceFragment sourceFragment;

	//******************************************************************************
	// onCreate
	//******************************************************************************
	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		// configure the activity
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_camera);

		// load the settings and cameras
		Utils.loadData();

		// get the networkCameraSource object
		Bundle data = getIntent().getExtras();
		networkCameraSource = data.getParcelable(CAMERA);

		// set the name
		nameEdit = (EditText) findViewById(R.id.camera_name);
		nameEdit.setText(networkCameraSource.name);

		// set the network
		TextView network = (TextView) findViewById(R.id.camera_network);
		network.setText(networkCameraSource.network);

		// set the source fragment
		sourceFragment = (SourceFragment)getSupportFragmentManager().findFragmentById(R.id.camera_source);
		sourceFragment.configure(networkCameraSource.source, true);
	}

	//******************************************************************************
	// onCreateOptionsMenu
	//******************************************************************************
	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.menu_save, menu);
		return true;
	}

	//******************************************************************************
	// onOptionsItemSelected
	//******************************************************************************
	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		int id = item.getItemId();

		// save the networkCameraSource
		if (id == R.id.action_save)
		{
			NetworkCameraSource editedNetworkCameraSource = getAndCheckEditedCamera();
			if (editedNetworkCameraSource != null)
			{
				List<NetworkCameraSource> networkCameraSources = Utils.getNetworkCameraSources();
				if (!networkCameraSource.name.isEmpty())
				{
					networkCameraSources.remove(networkCameraSource);
				}
				networkCameraSources.add(editedNetworkCameraSource);
				Utils.saveData();
				finish();
			}
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	//******************************************************************************
	// getAndCheckEditedCamera
	//******************************************************************************
	private NetworkCameraSource getAndCheckEditedCamera()
	{
		// create a new network and get the source
		NetworkCameraSource editedNetworkCameraSource = new NetworkCameraSource(networkCameraSource);

		// get and check the networkCameraSource name
		editedNetworkCameraSource.name = nameEdit.getText().toString().trim();
		if (editedNetworkCameraSource.name.isEmpty())
		{
			App.error(this, R.string.error_no_name);
			return null;
		}

		// make sure the name doesn't already exist
		String name = networkCameraSource.name;
		if (name.isEmpty() || !name.equals(editedNetworkCameraSource.name))
		{
			NetworkCameraSource existingNetworkCameraSource = Utils.findCamera(editedNetworkCameraSource.name);
			if (existingNetworkCameraSource != null)
			{
				App.error(this, R.string.error_name_already_exists);
				return null;
			}
		}

		// check the source values
		editedNetworkCameraSource.source = sourceFragment.getAndCheckEditedSource();
		if (editedNetworkCameraSource.source == null)
		{
			return null;
		}

		// return the successfully edited networkCameraSource
		return editedNetworkCameraSource;
	}
}