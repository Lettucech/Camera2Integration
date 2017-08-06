package com.gmail.brianbridge.camera2integration;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		Camera2BaseFragment fragment = new Camera2BaseFragment();
//		fragment.setDeniedLens(1,2,3,4);

		getSupportFragmentManager().beginTransaction()
				.replace(R.id.frameLayout_fragmentContainer, fragment)
				.commit();
	}
}
