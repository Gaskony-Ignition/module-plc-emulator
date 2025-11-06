# Logix L5K Parser - Version 1.0.17

Do you want to prototype with a Logix PLC but you can't connect to a live PLC? 
Now you can! This resource can parse a L5K export file and generate UDT definitions, tags, and even a simulation device using Ignition's Programmable Device Simulator. 
This resource will allow you to prototype against a Logix PLC using a simulator and when you are done you can swap out the simulation device with a real PLC device without any change to the tags.
The resource also builds out complete UDT definitions automatically with proper parameters. Never build a UDT definition in the PLC manually again.
Please enjoy!

## Installation

### Custom Instructions
**Installation**
Follow these steps:

+ Import the attached project to a dedicated project. The resource simply comes with a few Perspective views and a script library.
+ Open the project in the runtime and navigate to that page.

**Use Instructions**
Follow the steps outlined in the view:

+ First, we need to create a programmable device simulator unless you have a real PLC to connect to. Enter in the device name. Click the "Create Simulation Device" button if you want to create a programmable device simulator in Ignition.
+ Browse for your L5K export file. You can export that file from Logix5000.
+ Next, you can select the tags you want to import. The resource will automatically import all of the UDT definitions. You can select which tags within the UDTs you want to bring in. You can also select which global tags or program tags you want to bring in to Ignition. Simply click on the "selected" checkbox next to the tag.
+ If you are building a simulation device, you can enter in a simulation function for each tag. By default, we provide a static value that is read/write. However, you can easily add in a simulation function to generate random values or use any function from our Progammable Device Simulator. For UDT definitions, you can put the simulation function on those tags which will funnel down to each UDT instance automatically. More docs found here:



https://docs.inductiveautomation.com/display/DOC81/Programmable+Device+Simulator
+ Now you are ready to export the simulator program, UDT definitions, and tags. If you are using the programmable device simulator, click on the "Create Simulation Program Export" to get the instructions.csv. Once you have that, import it on the device in the Gateway configuration.
+ Next, you can export the UDT definitions. Click on the "Create UDT Export" button which will give you a udts.json file. Once you have that open your designer and import the UDTs into UDTs tab in any tag provider using the Tag Browser. Make sure to import UDTs before importing tags.
+ Lastly, you can import the tags. Click on the "Create Tag Export" button which will give you a tags.json file. Once you have that open your designer and import the tags into Tags tab in any tag provider using the Tag Browser.

That's it! Whether you are using the simulator or a real PLC, you should start seeing live values come in.
Please enjoy and feel free to contribute!

### Common Instructions

**Project (.zip/.proj)**  
Project backup and restoring from a project backup is referred to as Project Export and Import. Projects are exported individually, and only include project-specific elements visible in the Project Browser in the Ignition Designer. They do not include Gateway resources, like database connections, Tag Providers, Tags, and images. The exported file (.zip or .proj) is used to restore / import a project.

.zip = Ignition 8+
.proj = Ignition 7+

There are two primary ways to export and import a project:

Gateway Webpage - exports and imports the entire project.
Designer -  exports and imports only those resources that are selected.

When you restore / import a project from an exported file in the Gateway Webpage, it will be merged into your existing Gateway.

The import is located in:
Ignition Gateway > Configuration > System > Projects > Import Project Link

If there is a naming collision, you have the option of renaming the project or overwriting the project. Project exports can also be restored / imported in the Designer. Once the Designer is opened you can choose File > Import from the menu. This will even allow you to select which parts of the project import you want to include and will merge them into the currently open project.

### Requirements

**Modules**

+ Perspective

## Release Notes
- Fixed bug when there are no local tags in an AOI

## Authors and Acknowledgment
Built for the [Ignition Exchange](https://inductiveautomation.com/exchange) by Travis Cox

## Support
View [Logix L5K Parser](https://inductiveautomation.com/exchange/2114) for more information, and other [versions](https://inductiveautomation.com/exchange/2114/versions)

## License
+ [MIT](https://choosealicense.com/licenses/mit/)
+ [Terms & Conditions](https://inductiveautomation.com/exchange/terms)
+ [Acceptable Use](https://inductiveautomation.com/exchange/use)
