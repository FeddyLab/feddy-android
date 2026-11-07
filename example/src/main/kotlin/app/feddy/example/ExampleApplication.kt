package app.feddy.example

import android.app.Application
import app.feddy.sdk.Feddy

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Copy the project ID from the dashboard. Set projectId / apiUrl in
        // example/feddy.local.properties to point at your own project.
        Feddy.configure(this, projectId = BuildConfig.FEDDY_PROJECT_ID, apiUrl = BuildConfig.FEDDY_API_URL)
    }
}
