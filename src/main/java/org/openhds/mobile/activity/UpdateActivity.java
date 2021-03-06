package org.openhds.mobile.activity;

import org.openhds.mobile.Converter;
import org.openhds.mobile.FormsProviderAPI;
import org.openhds.mobile.InstanceProviderAPI;
import org.openhds.mobile.OpenHDS;
import org.openhds.mobile.Queries;
import org.openhds.mobile.R;
import org.openhds.mobile.database.DeathUpdate;
import org.openhds.mobile.database.ExternalInMigrationUpdate;
import org.openhds.mobile.database.HouseholdUpdate;
import org.openhds.mobile.database.InternalInMigrationUpdate;
import org.openhds.mobile.database.LocationUpdate;
import org.openhds.mobile.database.MembershipUpdate;
import org.openhds.mobile.database.OutMigrationUpdate;
import org.openhds.mobile.database.PregnancyOutcomeUpdate;
import org.openhds.mobile.database.RelationshipUpdate;
import org.openhds.mobile.database.Updatable;
import org.openhds.mobile.database.VisitUpdate;
import org.openhds.mobile.fragment.EventFragment;
import org.openhds.mobile.fragment.ProgressFragment;
import org.openhds.mobile.fragment.SelectionFragment;
import org.openhds.mobile.fragment.ValueFragment;
import org.openhds.mobile.listener.OdkFormLoadListener;
import org.openhds.mobile.model.FieldWorker;
import org.openhds.mobile.model.FilledForm;
import org.openhds.mobile.model.Form;
import org.openhds.mobile.model.FormFiller;
import org.openhds.mobile.model.Individual;
import org.openhds.mobile.model.Location;
import org.openhds.mobile.model.LocationHierarchy;
import org.openhds.mobile.model.LocationVisit;
import org.openhds.mobile.model.PregnancyObservationUpdate;
import org.openhds.mobile.model.PregnancyOutcome;
import org.openhds.mobile.model.Round;
import org.openhds.mobile.model.SocialGroup;
import org.openhds.mobile.model.StateMachine;
import org.openhds.mobile.model.StateMachine.State;
import org.openhds.mobile.task.OdkGeneratedFormLoadTask;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentResolver;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.SimpleCursorAdapter;
import android.widget.Toast;

/**
 * UpdateActivity mediates the interaction between the 3 column fragments. The
 * buttons in the left most column drive a state machine while the user
 * interacts with the application.
 */
public class UpdateActivity extends Activity implements ValueFragment.ValueListener, LoaderCallbacks<Cursor>,
        EventFragment.Listener, SelectionFragment.Listener {

    private SelectionFragment sf;
    private ValueFragment vf;
    private EventFragment ef;
    private ProgressFragment progressFragment;
    private MenuItem  menuItemForm;

    // loader ids
    private static final int SOCIAL_GROUP_AT_LOCATION = 0;
    private static final int SOCIAL_GROUP_FOR_INDIVIDUAL = 10;
    private static final int SOCIAL_GROUP_FOR_EXT_INMIGRATION = 20;
    
    // activity request codes for onActivityResult
    private static final int SELECTED_XFORM = 1;
    private static final int CREATE_LOCATION = 10;
    private static final int FILTER_RELATIONSHIP = 20;
    private static final int FILTER_LOCATION = 30;
    private static final int FILTER_FORM = 35;
    private static final int FILTER_INMIGRATION = 40;
    private static final int FILTER_BIRTH_FATHER = 45;
    private static final int LOCATION_GEOPOINT = 50;
    protected static final int FILTER_INMIGRATION_MOTHER = 60;
    protected static final int FILTER_INMIGRATION_FATHER = 70;
    protected static final int FILTER_INDIV_VISIT = 75;
    // the uri of the last viewed xform
    private Uri contentUri;

    // status flags indicating a dialog, used for restoring the activity
    private boolean formUnFinished = false;
    private boolean xFormNotFound = false;

    private AlertDialog householdDialog;

    private final FormFiller formFiller = new FormFiller();
    private final StateMachine stateMachine = new StateMachine();

    private LocationVisit locationVisit = new LocationVisit();
    private FilledForm filledForm;
    private AlertDialog xformUnfinishedDialog;
    private boolean showingProgress;
    private Updatable updatable;
    private boolean extInm;
    private String jrFormId;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        FieldWorker fw = (FieldWorker) getIntent().getExtras().getSerializable("fieldWorker");
        locationVisit.setFieldWorker(fw);

        vf = new ValueFragment();
        FragmentTransaction txn = getFragmentManager().beginTransaction();
        txn.add(R.id.middle_col, vf).commit();

        sf = (SelectionFragment) getFragmentManager().findFragmentById(R.id.selectionFragment);
        sf.setLocationVisit(locationVisit);

        ef = (EventFragment) getFragmentManager().findFragmentById(R.id.eventFragment);
        ef.setLocationVisit(locationVisit);

        ActionBar actionBar = getActionBar();
        actionBar.show();

        restoreState(savedInstanceState);
    }

    /**
     * The main menu, showing multiple options
     */
    
    @Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.formmenu, menu);
        this.menuItemForm = menu.getItem(0);
        menu.getItem(0).setVisible(false);
        super.onCreateOptionsMenu(menu);
        return true;
	}

    /**
     * Defining what happens when a main menu item is selected
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.extra_forms:
        	createFormMenu();
            return true;
        case R.id.sync_database:
            createSyncDatabaseMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	ContentResolver resolver = getContentResolver();
    	Cursor cursor = null;
        switch (requestCode) {
        case SELECTED_XFORM:
        	handleXformResult(resultCode, data);
            break;
        case FILTER_FORM:
        	 if (resultCode != RESULT_OK) {
                 return;
             }
        	Form form =(Form) data.getExtras().getSerializable("form");
        	SocialGroup sg = null;
        	cursor = Queries.getSocialGroupsByIndividualExtId(resolver,locationVisit.getSelectedIndividual().getExtId());
        	if (cursor.moveToFirst()) {
        	sg = Converter.convertToSocialGroup(cursor);
        	locationVisit.getLocation().setHead(sg.getGroupHead());
        	}
        	filledForm = formFiller.fillExtraForm(locationVisit, form.getName(), sg);
        	cursor.close();
        	loadForm(SELECTED_XFORM);
        	break;
        case FILTER_BIRTH_FATHER:
            handleFatherBirthResult(resultCode, data);
            break;
        case CREATE_LOCATION:
            handleLocationCreateResult(resultCode, data);
            break;
        case FILTER_RELATIONSHIP:
            handleFilterRelationshipResult(resultCode, data);
            break;
        case FILTER_LOCATION:
            if (resultCode != RESULT_OK) {
                return;
            }
        	Location location1 = (Location) data.getExtras().getSerializable("location");
        	locationVisit.setLocation(location1);
        	vf.onLoaderReset(null);
            stateMachine.transitionTo(State.CREATE_VISIT);
            break;
        case FILTER_INMIGRATION:
            handleFilterInMigrationResult(resultCode, data);
            break;
        case FILTER_INMIGRATION_MOTHER:
            handleFilterMother(resultCode, data);
            break;
        case FILTER_INDIV_VISIT:
            handleFilterIndivVisit(resultCode, data);
            break;
        case FILTER_INMIGRATION_FATHER:
            handleFilterFather(resultCode, data);
            break;
        case LOCATION_GEOPOINT:
            if (resultCode == RESULT_OK) {
                String extId = data.getExtras().getString("extId");
                // a few things need to happen here:
                // * get the location by extId
                cursor = Queries.getLocationByExtId(resolver, extId);
                Location location = Converter.toLocation(cursor);
               
                // * figure out the parent location hierarchy
                cursor = Queries.getHierarchyByExtId(resolver, location.getHierarchy());
                LocationHierarchy subvVllage = Converter.toHierarhcy(cursor, true);
                

                cursor = Queries.getHierarchyByExtId(resolver, subvVllage.getParent());
                LocationHierarchy village = Converter.toHierarhcy(cursor, true);
                
                cursor = Queries.getHierarchyByExtId(resolver, village.getParent());
                LocationHierarchy district = Converter.toHierarhcy(cursor, true);
                
                cursor = Queries.getHierarchyByExtId(resolver, district.getParent());
                LocationHierarchy region = Converter.toHierarhcy(cursor, true);
                
                cursor = Queries.allRounds(resolver);
                Round round = Converter.convertToRound(cursor);
                
                locationVisit.setHierarchy1(region);
                locationVisit.setHierarchy2(district);
                locationVisit.setHierarchy3(village);
                locationVisit.setHierarchy4(subvVllage);
                locationVisit.setRound(round);
                locationVisit.setLocation(location);
                
                
                sf.setLocationVisit(locationVisit);
                sf.setAll();
            	vf.onLoaderReset(null);
                stateMachine.transitionTo(State.CREATE_VISIT);
                cursor.close();
            }
        }
    }

    private void handleFilterIndivVisit(int resultCode, Intent data) {
            if (resultCode != RESULT_OK) {
                return;
            }

            Individual individual = (Individual) data.getExtras().getSerializable("individual");
            if (individual!=null) 
            filledForm.setIntervieweeId(individual.getExtId());	
            loadForm(SELECTED_XFORM);
        

	}

	private void handleFatherBirthResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }

        Individual individual = (Individual) data.getExtras().getSerializable("individual");
        new CreatePregnancyOutcomeTask(individual).execute();
    }

    private void handleLocationCreateResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            showProgressFragment();
            new CheckLocationFormStatus(getContentResolver(), contentUri).execute();
        } else {
            Toast.makeText(this, getString(R.string.odk_problem_lbl), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * This differs from {@link UpdateActivity.CheckFormStatus} in that, upon
     * creating a new location, the user is automatically forwarded to creating
     * a visit. This happens because the user could in theory create a location,
     * and then skip the visit.
     */
    class CheckLocationFormStatus extends AsyncTask<Void, Void, Boolean> {

        private ContentResolver resolver;
        private Uri contentUri;

        public CheckLocationFormStatus(ContentResolver resolver, Uri contentUri) {
            this.resolver = resolver;
            this.contentUri = contentUri;
        }

        @Override
        protected Boolean doInBackground(Void... arg0) {
            Cursor cursor = resolver.query(contentUri, new String[] { InstanceProviderAPI.InstanceColumns.STATUS,
                    InstanceProviderAPI.InstanceColumns.INSTANCE_FILE_PATH },
                    InstanceProviderAPI.InstanceColumns.STATUS + "=?",
                    new String[] { InstanceProviderAPI.STATUS_COMPLETE }, null);
            if (cursor.moveToNext()) {
                String filepath = cursor.getString(cursor
                        .getColumnIndex(InstanceProviderAPI.InstanceColumns.INSTANCE_FILE_PATH));
                LocationUpdate update = new LocationUpdate();
                update.updateDatabase(resolver, filepath, jrFormId);
                cursor.close();
                return true;
            } else {
                cursor.close();
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            hideProgressFragment();

            if (result) {
                stateMachine.transitionTo(State.CREATE_VISIT);
            } else {
                createUnfinishedFormDialog();
            }
        }
    }

    private void handleFilterInMigrationResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }

        showProgressFragment();
        Individual individual = (Individual) data.getExtras().getSerializable("individual");
        //extInm= false;


        new CreateInternalInMigrationTask(individual).execute();
        locationVisit.setSelectedIndividual(individual);

        stateMachine.transitionTo(State.INMIGRATION);
        
    }

    private class CreateInternalInMigrationTask extends AsyncTask<Void, Void, Void> {

        private Individual individual;
        
        public CreateInternalInMigrationTask(Individual individual) {
            this.individual = individual;
        }

        @Override
        protected Void doInBackground(Void... params) {
            filledForm = formFiller.fillInternalInMigrationForm(locationVisit, individual);
            updatable = new InternalInMigrationUpdate();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            loadForm(SELECTED_XFORM);
        }
    }

    
    private void handleFilterRelationshipResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }

        Individual individual = (Individual) data.getExtras().getSerializable("individual");
        filledForm.setIndividualB(individual.getExtId());

        loadForm(SELECTED_XFORM);
    }

    private void handleXformResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            showProgressFragment();
            new CheckFormStatus(getContentResolver(), contentUri).execute();
        } else {
            Toast.makeText(this, getString(R.string.odk_problem_lbl), Toast.LENGTH_LONG).show();
        }
    }

    private void handleFilterFather(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }

        Individual individual = (Individual) data.getExtras().getSerializable("individual");
        filledForm.setFatherExtId(individual.getExtId());
        filledForm.setIndividualLastName(individual.getLastName());
        filledForm.setIndividualMiddleName(individual.getFirstName());
        loadForm(SELECTED_XFORM);
    }

    private void handleFilterMother(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }

        Individual individual = (Individual) data.getExtras().getSerializable("individual");
        filledForm.setMotherExtId(individual.getExtId());

        buildFatherDialog();
    }

    private void showProgressFragment() {
        if (showingProgress) {
            return;
        }

        if (progressFragment == null) {
            progressFragment = ProgressFragment.newInstance();
        }

        showingProgress = true;
        FragmentTransaction txn = getFragmentManager().beginTransaction();
        txn.remove(progressFragment);
        txn.add(R.id.middle_col, progressFragment).commit();
    }

    void hideProgressFragment() {
        if (!showingProgress) {
            return;
        }

        showingProgress = false;
        FragmentTransaction txn = getFragmentManager().beginTransaction();
        if (!vf.isAdded()) {
        	txn.add(R.id.middle_col, vf).commitAllowingStateLoss();
        } else {
        	txn.show(vf);
        }
        
    }

    /**
     * AsyncTask that attempts to get the status of the form that the user just
     * filled out. In ODK, when a form is saved and marked as complete, its
     * status is set to {@link InstanceProviderAPI.STATUS_COMPLETE}. If the user
     * leaves the form in ODK before saving it, the status will not be set to
     * complete. Alternatively, the user could save the form, but not mark it as
     * complete. Since there is no way to tell the difference between the user
     * leaving the form without completing, or saving without marking as
     * complete, we enforce that the form be marked as complete before the user
     * can continue with update events. They have 2 options: go back to the form
     * and save it as complete, or delete the previously filled form.
     */
    class CheckFormStatus extends AsyncTask<Void, Void, Boolean> {

        private ContentResolver resolver;
        private Uri contentUri;

        public CheckFormStatus(ContentResolver resolver, Uri contentUri) {
            this.resolver = resolver;
            this.contentUri = contentUri;
        }

        @Override
        protected Boolean doInBackground(Void... arg0) {
            Cursor cursor = resolver.query(contentUri, new String[] { InstanceProviderAPI.InstanceColumns.STATUS,
                    InstanceProviderAPI.InstanceColumns.INSTANCE_FILE_PATH },
                    InstanceProviderAPI.InstanceColumns.STATUS + "=?",
                    new String[] { InstanceProviderAPI.STATUS_COMPLETE }, null);
            if (cursor.moveToNext()) {
                String filepath = cursor.getString(cursor
                        .getColumnIndex(InstanceProviderAPI.InstanceColumns.INSTANCE_FILE_PATH));
                updatable.updateDatabase(getContentResolver(), filepath, jrFormId);
                cursor.close();
                return true;
            } else {
                cursor.close();
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            hideProgressFragment();

            if (result) {
            	if (stateMachine.getState()==State.INMIGRATION) {
            		stateMachine.transitionTo(State.SELECT_EVENT);
            		if (extInm)
                		onFinishExternalInmigration();
            	} else if (stateMachine.getState()==State.SELECT_INDIVIDUAL) {
            		if (extInm)
                		onFinishExternalInmigration();
            	}else {
            		stateMachine.transitionTo(State.SELECT_INDIVIDUAL);
            	}
            } else {
                createUnfinishedFormDialog();
            }
        }
    }

    /**
     * At any given point in time, the screen can be rotated. This method is
     * responsible for saving the screen state.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putSerializable("locationvisit", locationVisit);
        outState.putString("currentState", stateMachine.getState().toString());
        outState.putBoolean("unfinishedFormDialog", formUnFinished);
        outState.putBoolean("xFormNotFound", xFormNotFound);

        if (contentUri != null)
            outState.putString("uri", contentUri.toString());
    }

    /**
     * This method is responsible for restoring the screen state.
     */
    private void restoreState(Bundle savedState) {
        State state = State.SELECT_HIERARCHY_1;
        if (savedState != null) {
            locationVisit = (LocationVisit) savedState.getSerializable("locationvisit");

            String uri = savedState.getString("uri");
            if (uri != null)
                contentUri = Uri.parse(uri);

            if (savedState.getBoolean("xFormNotFound"))
                createXFormNotFoundDialog();
            if (savedState.getBoolean("unfinishedFormDialog"))
                createUnfinishedFormDialog();

            sf.setLocationVisit(locationVisit);
            ef.setLocationVisit(locationVisit);

            String currentState = savedState.getString("currentState");
            state = State.valueOf(currentState);
        }

        registerTransitions();
        stateMachine.transitionInSequence(state);
    }

    /**
     * Creates the 'Configure Server' option in the action menu.
     */
    private void createFormMenu() {
        Intent i = new Intent(this, FilterFormActivity.class);
        i.putExtra("location", locationVisit);
        startActivityForResult(i, FILTER_FORM);
    }

    /**
     * Creates the 'Sync Database' option in the action menu.
     */
    private void createSyncDatabaseMenu() {
        Intent i = new Intent(this, SyncDatabaseActivity.class);
        startActivity(i);
    }

    /**
     * Method used for starting the activity for filtering for individuals
     */
    private void startFilterActivity(int requestCode) {
    	Intent i =null;
    	if (requestCode==75) {
            i = new Intent(this, FilterVisitActivity.class);
    	} else {
    		i = new Intent(this, FilterActivity.class);
    	}
    	

        i.putExtra("hierarchy1", locationVisit.getHierarchy1());
        i.putExtra("hierarchy2", locationVisit.getHierarchy2());
        i.putExtra("hierarchy3", locationVisit.getHierarchy3());
        i.putExtra("hierarchy4", locationVisit.getHierarchy4());
        

        Location loc = locationVisit.getLocation();
        if (loc == null) {
            loc = Location.emptyLocation();
        }
        i.putExtra("location", loc);

        switch (requestCode) {
        case FILTER_INMIGRATION_MOTHER:
            i.putExtra("requireGender", "F");
            break;
        case FILTER_BIRTH_FATHER:
            i.putExtra("requireGender", "M");
        case FILTER_INMIGRATION:
            i.putExtra("img", "IMG");
        }

        startActivityForResult(i, requestCode);
    }
    
    
    /**
     * Method used for starting the activity for filtering for Locations
     */
    private void startFilterLocActivity(int requestCode) {
        Intent i = new Intent(this, FilterLocationActivity.class);
        i.putExtra("hierarchy1", locationVisit.getHierarchy1());
        i.putExtra("hierarchy2", locationVisit.getHierarchy2());
        i.putExtra("hierarchy3", locationVisit.getHierarchy3());
        i.putExtra("hierarchy4", locationVisit.getHierarchy4());

        Location loc = locationVisit.getLocation();
        if (loc == null) {
            loc = Location.emptyLocation();
        }
        i.putExtra("location", loc);


        startActivityForResult(i, requestCode);
    }    
    
    
    
    

    private void loadHierarchy1ValueData() {
        vf.loadLocationHierarchy();
    }

    private void loadHierarchy2ValueData() {
        vf.loadHierarchy2(locationVisit.getHierarchy1().getExtId());
    }
    
    private void loadHierarchy3ValueData() {
        vf.loadHierarchy3(locationVisit.getHierarchy2().getExtId());
    }

    private void loadHierarchy4ValueData() {
        vf.loadHierarchy4(locationVisit.getHierarchy3().getExtId());
    }

    private void loadLocationValueData() {
        vf.loadLocations(locationVisit.getHierarchy4().getExtId());
    }

    private void loadRoundValueData() {
        vf.loadRounds();
    }

    private void loadIndividualValueData() {
        vf.loadIndividuals(locationVisit.getLocation().getExtId());
    }

    private void createUnfinishedFormDialog() {
        formUnFinished = true;
        if (xformUnfinishedDialog == null) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
            alertDialogBuilder.setTitle(getString(R.string.warning_lbl));
            alertDialogBuilder.setMessage(getString(R.string.update_unfinish_msg1));
            alertDialogBuilder.setCancelable(true);
            alertDialogBuilder.setPositiveButton(getString(R.string.update_unfinish_pos_button), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    formUnFinished = false;
                    xformUnfinishedDialog.hide();
                    getContentResolver().delete(contentUri, InstanceProviderAPI.InstanceColumns.STATUS + "=?",
                            new String[] { InstanceProviderAPI.STATUS_INCOMPLETE });
                }
            });
            alertDialogBuilder.setNegativeButton(getString(R.string.update_unfinish_neg_button), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    formUnFinished = false;
                    xformUnfinishedDialog.hide();
                    startActivityForResult(new Intent(Intent.ACTION_EDIT, contentUri), SELECTED_XFORM);
                }
            });
            xformUnfinishedDialog = alertDialogBuilder.create();
        }

        xformUnfinishedDialog.show();
    }

    private void createXFormNotFoundDialog() {
        xFormNotFound = true;
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
          alertDialogBuilder.setTitle(getString(R.string.warning_lbl));
          alertDialogBuilder.setMessage(getString(R.string.update_xform_not_found_msg));
          alertDialogBuilder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                xFormNotFound = false;
            }
        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    public void onLocationGeoPoint() {
        Intent intent = new Intent(getApplicationContext(), ShowMapActivity.class);
        startActivityForResult(intent, LOCATION_GEOPOINT);
    }

    public void onCreateLocation() {
        showProgressFragment();
        new GenerateLocationTask().execute();
    }

    private class GenerateLocationTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            locationVisit.createLocation(getContentResolver());
            filledForm = formFiller.fillLocationForm(locationVisit);
            updatable = new LocationUpdate();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            loadForm(CREATE_LOCATION);
        }

    }

    public void onCreateVisit() {
        showProgressFragment();
        new CreateVisitTask().execute();
    }

    private class CreateVisitTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            locationVisit.createVisit(getContentResolver());
            filledForm = formFiller.fillVisitForm(locationVisit);
            updatable = new VisitUpdate();
        	startFilterActivity(FILTER_INDIV_VISIT);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
        }
    }

    public void onFinishVisit() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.visit_lbl));
        alertDialogBuilder.setMessage(getString(R.string.update_finish_visit_msg));
        alertDialogBuilder.setCancelable(true);
        alertDialogBuilder.setPositiveButton(getString(R.string.yes_lbl), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            	if(menuItemForm != null) {
                  	menuItemForm.setVisible(false);
                 }    
            	locationVisit = locationVisit.completeVisit();
                sf.setLocationVisit(locationVisit);
                ef.setLocationVisit(locationVisit);
                stateMachine.transitionTo(State.FINISH_VISIT);
                stateMachine.transitionTo(State.SELECT_LOCATION);
                vf.onLoaderReset(null);    
                }
        });
        alertDialogBuilder.setNegativeButton(getString(R.string.cancel_lbl), null);
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    public void onHousehold() {
        showProgressFragment();
        new CreateSocialGroupTask().execute();
    }

    private class CreateSocialGroupTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            SocialGroup sg = locationVisit.createSocialGroup(getContentResolver());
            if (sg==null){
            	//this.cancel(true);
            	//hideProgressFragment();
            	//onSGexists();
            } else {
            	filledForm = formFiller.fillSocialGroupForm(locationVisit, sg);
            	updatable = new HouseholdUpdate();
            }
            
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
        	 SocialGroup sg = locationVisit.createSocialGroup(getContentResolver());
             if (sg==null){
            	onSGexists();
            	this.cancel(true);
             	hideProgressFragment();
             	
             } else {
            	hideProgressFragment();
            	loadForm(SELECTED_XFORM);
             }
        }
    }
    
    public void onSGexists() {
    	 AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
         alertDialogBuilder.setTitle(getString(R.string.socialgroup_lbl));
         alertDialogBuilder.setMessage(getString(R.string.update_on_sgexists_msg));
         alertDialogBuilder.setCancelable(true);
         alertDialogBuilder.setPositiveButton("Ok", null);
         AlertDialog alertDialog = alertDialogBuilder.create();
         alertDialog.show();         
    }

    public void onMembership() {
        filledForm = formFiller.fillMembershipForm(locationVisit);
        updatable = new MembershipUpdate();
        showProgressFragment();
        getLoaderManager().restartLoader(SOCIAL_GROUP_AT_LOCATION, null, this);
    }

    public void onRelationship() {
        filledForm = formFiller.fillRelationships(locationVisit);
        updatable = new RelationshipUpdate();
        startFilterActivity(FILTER_RELATIONSHIP);
    }

    public void onInMigration() {
        createInMigrationFormDialog();
    }

    private void createInMigrationFormDialog() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.in_migration_lbl));
        alertDialogBuilder.setMessage(getString(R.string.update_create_inmigration_msg));
        alertDialogBuilder.setCancelable(true);
        alertDialogBuilder.setPositiveButton(getString(R.string.update_create_inmigration_pos_button), new DialogInterface.OnClickListener() {
        	 public void onClick(DialogInterface dialog, int which) {
            	extInm= true;
            	startFilterActivity(FILTER_INMIGRATION);
     
            }
        });
             alertDialogBuilder.setNegativeButton(getString(R.string.update_create_inmigration_neg_button), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                showProgressFragment();
                extInm= true;
                new CreateExternalInmigrationTask().execute();

            }
        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private class CreateExternalInmigrationTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            String id = locationVisit.generateIndividualId(getContentResolver());
            filledForm = formFiller.fillExternalInmigration(locationVisit, id);
            updatable = new ExternalInMigrationUpdate();            
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            buildMotherDialog();
         }


    }
    
	private void onFinishExternalInmigration() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.in_migration_lbl));
        alertDialogBuilder.setMessage(getString(R.string.update_finish_ext_inmigration_msg));
        alertDialogBuilder.setCancelable(true);
        alertDialogBuilder.setPositiveButton("Ok", null);
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();			
	}

    private void buildMotherDialog() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.mother_lbl));
        alertDialogBuilder.setMessage(getString(R.string.update_build_mother_msg));
        alertDialogBuilder.setCancelable(true);
        alertDialogBuilder.setPositiveButton(getString(R.string.yes_lbl), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                startFilterActivity(FILTER_INMIGRATION_MOTHER);
            }
        });
        alertDialogBuilder.setNegativeButton(getString(R.string.no_lbl), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                filledForm.setMotherExtId("UNK");
                buildFatherDialog();
            }
        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void buildFatherDialog() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
       alertDialogBuilder.setTitle(getString(R.string.father_lbl));
        alertDialogBuilder.setMessage(getString(R.string.update_build_father_msg));
        alertDialogBuilder.setCancelable(true);
        alertDialogBuilder.setPositiveButton(getString(R.string.yes_lbl), new DialogInterface.OnClickListener() {
        	  public void onClick(DialogInterface dialog, int which) {
                startFilterActivity(FILTER_INMIGRATION_FATHER);
            }
        });
        alertDialogBuilder.setNegativeButton(getString(R.string.no_lbl), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                filledForm.setFatherExtId("UNK");
                loadForm(SELECTED_XFORM);
            }
        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    public void onOutMigration() {
        showProgressFragment();
        new CreateOutMigrationTask().execute();
    }

    private class CreateOutMigrationTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            filledForm = formFiller.fillOutMigrationForm(locationVisit);
            updatable = new OutMigrationUpdate();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            loadForm(SELECTED_XFORM);
        }
    }

    public void onPregnancyRegistration() {
        showProgressFragment();
        new CreatePregnancyObservationTask().execute();
    }

    private class CreatePregnancyObservationTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            filledForm = formFiller.fillPregnancyRegistrationForm(locationVisit);
            updatable = new PregnancyObservationUpdate();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            loadForm(SELECTED_XFORM);
        }
    }

    /**
     * The pregnancy outcome flow is as follows: <br />
     * 1. Prompt user for the number of live births. This indicates how many
     * child ids will be generated. <br />
     * 2. Prompt user for the father. We attempt to determine the father by
     * looking at any relationships the mother has. The user also has the option
     * of searching for the father as well. <br />
     * 3. Prompt for the social group to use. In this scenario, a search is made
     * for all memberships present at a location.
     */
    public void onPregnancyOutcome() {
        buildPregnancyLiveBirthCountDialog();
    }

    private void buildPregnancyLiveBirthCountDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.update_build_pregnancy_lbr_count_msg)).setCancelable(true)
                .setItems(new String[] {"1", "2", "3", "4" }, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        showProgressFragment();
                        new PregnancyOutcomeFatherSelectionTask(which+1).execute();
                    }
                });
        builder.show();
    }

    private class PregnancyOutcomeFatherSelectionTask extends AsyncTask<Void, Void, Individual> {

        private int liveBirthCount;

        public PregnancyOutcomeFatherSelectionTask(int liveBirthCount) {
            this.liveBirthCount = liveBirthCount;
        }

        @Override
        protected Individual doInBackground(Void... params) {
            PregnancyOutcome pregOut = locationVisit.createPregnancyOutcome(getContentResolver(), liveBirthCount);
            filledForm = formFiller.fillPregnancyOutcome(locationVisit, pregOut);
            updatable = new PregnancyOutcomeUpdate();
            final Individual father = locationVisit.determinePregnancyOutcomeFather(getContentResolver());
            return father;
        }

        @Override
        protected void onPostExecute(final Individual father) {
            hideProgressFragment();

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(UpdateActivity.this);
            alertDialogBuilder.setTitle(getString(R.string.update_pregoutcome_choose_father));
            alertDialogBuilder.setCancelable(true);

            if (father != null) {
                String fatherName = father.getFullName() + " (" + father.getExtId() + ")";
                String items[] = { fatherName, getString(R.string.update_pregoutcome_search_hdss), getString(R.string.update_pregoutcome_father_not_found) };
                alertDialogBuilder.setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int choice) {
                        if (choice == 0) {
                            new CreatePregnancyOutcomeTask(father).execute();
                        } else if (choice == 1) {
                            // choose father
                            startFilterActivity(FILTER_BIRTH_FATHER);
                        } else if (choice == 2) {
                            new CreatePregnancyOutcomeTask(null).execute();
                        }
                    }
                });
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.fatherNotFound), Toast.LENGTH_LONG).show();
                String items[] = { getString(R.string.update_pregoutcome_search_hdss), getString(R.string.update_pregoutcome_not_within_hdss) };
                alertDialogBuilder.setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int choice) {
                        if (choice == 0) {
                            startFilterActivity(FILTER_BIRTH_FATHER);
                        } else if (choice == 1) {
                            new CreatePregnancyOutcomeTask(null).execute();
                        }
                    }
                });
            }

            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        }
    }

    private class CreatePregnancyOutcomeTask extends AsyncTask<Void, Void, Void> {

        private Individual father;

        public CreatePregnancyOutcomeTask(Individual father) {
            this.father = father;
        }

        @Override
        protected Void doInBackground(Void... params) {
            String fatherId = father == null ? "UNK" : father.getExtId();
            formFiller.appendFatherId(filledForm, fatherId);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            loadSocialGroupsForIndividual();
        }
    }

    public void onDeath() {
        showProgressFragment();
        new CreateDeathTask().execute();
    }

    
  
    
    private class CreateDeathTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
        	SocialGroup sg = null;
        	ContentResolver resolver = getContentResolver();
        	Cursor cursor = Queries.getSocialGroupsByIndividualExtId(resolver,locationVisit.getSelectedIndividual().getExtId());
        	if (cursor.moveToFirst()) {
        	sg = Converter.convertToSocialGroup(cursor);
        	locationVisit.getLocation().setHead(sg.getGroupHead());
        	}
            filledForm = formFiller.fillDeathForm(locationVisit, sg);
            
            updatable = new DeathUpdate();
            cursor.close();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            hideProgressFragment();
            loadForm(SELECTED_XFORM);
        }
    }

    private void loadSocialGroupsForIndividual() {
        showProgressFragment();
        getLoaderManager().restartLoader(SOCIAL_GROUP_FOR_INDIVIDUAL, null, this);
    }

    public void onClearIndividual() {
        locationVisit.setSelectedIndividual(null);
        stateMachine.transitionTo(State.SELECT_INDIVIDUAL);
        
        if(this.menuItemForm != null) {
        	this.menuItemForm.setVisible(false);
        }
    }

    public void loadForm(final int requestCode) {
        new OdkGeneratedFormLoadTask(getBaseContext(), filledForm, new OdkFormLoadListener() {
            public void onOdkFormLoadSuccess(Uri contentUri) {
            	Cursor cursor = getCursorForFormsProvider(filledForm.getFormName());
                if (cursor.moveToFirst()) {
                    jrFormId = cursor.getString(0);
                }
                UpdateActivity.this.contentUri = contentUri;
                startActivityForResult(new Intent(Intent.ACTION_EDIT, contentUri), requestCode);
            }

            public void onOdkFormLoadFailure() {
                createXFormNotFoundDialog();
            }
        }).execute();
    }

    public void onHierarchy1() {
        locationVisit.clearLevelsBelow(0);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_1);
        loadHierarchy1ValueData();
    }

    public void onHierarchy2() {
        locationVisit.clearLevelsBelow(1);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_2);
        loadHierarchy2ValueData();
    }
    
    public void onHierarchy3() {
        locationVisit.clearLevelsBelow(2);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_3);
        loadHierarchy3ValueData();
    }

    public void onHierarchy4() {
        locationVisit.clearLevelsBelow(3);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_4);
        loadHierarchy4ValueData();
    }

    public void onLocation() {
        locationVisit.clearLevelsBelow(5);
        stateMachine.transitionTo(State.SELECT_LOCATION);
        loadLocationValueData();
    }

    public void onRound() {
        locationVisit.clearLevelsBelow(4);
        stateMachine.transitionTo(State.SELECT_ROUND);
        loadRoundValueData();
    }

    public void onIndividual() {
        locationVisit.clearLevelsBelow(6);
        loadIndividualValueData();
    }

    public void onHierarchy1Selected(LocationHierarchy hierarchy) {
        locationVisit.setHierarchy1(hierarchy);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_2);
    }

    private void registerTransitions() {
        sf.registerTransitions(stateMachine);
        ef.registerTransitions(stateMachine);
    }

    public void onHierarchy2Selected(LocationHierarchy subregion) {
        locationVisit.setHierarchy2(subregion);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_3);
    }
    

    public void onHierarchy3Selected(LocationHierarchy hierarchy) {
        locationVisit.setHierarchy3(hierarchy);
        stateMachine.transitionTo(State.SELECT_HIERARCHY_4);
    }

    public void onHierarchy4Selected(LocationHierarchy village) {
        locationVisit.setHierarchy4(village);
        stateMachine.transitionTo(State.SELECT_ROUND);
    }

    public void onRoundSelected(Round round) {
        locationVisit.setRound(round);
        stateMachine.transitionTo(State.SELECT_LOCATION);
    }

    public void onLocationSelected(Location location) {
        locationVisit.setLocation(location);
        stateMachine.transitionTo(State.CREATE_VISIT);
    }

    public void onIndividualSelected(Individual individual) {
        locationVisit.setSelectedIndividual(individual);
        stateMachine.transitionTo(State.SELECT_EVENT);
        
        if(this.menuItemForm != null) {
        	this.menuItemForm.setVisible(true);
        }
       
    }

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Uri uri = null;
        switch (id) {
        case SOCIAL_GROUP_AT_LOCATION:
        case SOCIAL_GROUP_FOR_EXT_INMIGRATION:
            uri = OpenHDS.SocialGroups.CONTENT_LOCATION_ID_URI_BASE.buildUpon()
                    .appendPath(locationVisit.getLocation().getExtId()).build();
            break;
        case SOCIAL_GROUP_FOR_INDIVIDUAL:
            uri = OpenHDS.SocialGroups.CONTENT_INDIVIDUAL_ID_URI_BASE.buildUpon()
                    .appendPath(locationVisit.getSelectedIndividual().getExtId()).build();
            break;
        }

        return new CursorLoader(this, uri, null, null, null, null);
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        hideProgressFragment();
        if (cursor.getCount() == 1 && loader.getId() == SOCIAL_GROUP_FOR_INDIVIDUAL) {
            cursor.moveToFirst();
            appendSocialGroupFromCursor(cursor);
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.update_load_finished_select_hh_msg));
        SimpleCursorAdapter adapter = new SimpleCursorAdapter(this, android.R.layout.simple_list_item_2, cursor,
                new String[] { OpenHDS.SocialGroups.COLUMN_SOCIALGROUP_GROUPNAME,
                        OpenHDS.SocialGroups.COLUMN_SOCIALGROUP_EXTID }, new int[] { android.R.id.text1,
                        android.R.id.text2 }, 0);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                Cursor cursor = (Cursor) householdDialog.getListView().getItemAtPosition(which);
                appendSocialGroupFromCursor(cursor);
            }
        });
        builder.setNegativeButton(getString(R.string.cancel_lbl), null);
        householdDialog = builder.create();
        householdDialog.show();
    }

    private void appendSocialGroupFromCursor(Cursor cursor) {
        SocialGroup sg = Converter.convertToSocialGroup(cursor);
        filledForm = formFiller.appendSocialGroup(sg, filledForm);
        loadForm(SELECTED_XFORM);
    }

    public void onLoaderReset(Loader<Cursor> arg0) {
        householdDialog.dismiss();
        householdDialog = null;
    }

	public void onFilterLocation() {
		startFilterLocActivity(FILTER_LOCATION);	
		
	}
    private Cursor getCursorForFormsProvider(String name) {
    	ContentResolver resolver = getContentResolver();
        return resolver.query(FormsProviderAPI.FormsColumns.CONTENT_URI, new String[] {
                FormsProviderAPI.FormsColumns.JR_FORM_ID, FormsProviderAPI.FormsColumns.FORM_FILE_PATH },
                FormsProviderAPI.FormsColumns.JR_FORM_ID + " like ?", new String[] { name + "%" }, null);
    }
}
