/*******************************************************************************
 * Copyright 2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package emlab.gen.domain.technology;

import org.neo4j.graphdb.Direction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.annotation.NodeEntity;
import org.springframework.data.neo4j.annotation.RelatedTo;

import emlab.gen.domain.gis.Zone;
import emlab.gen.trend.HourlyCSVTimeSeries;

@NodeEntity
public class PowerGridNode {

    String name;

    @RelatedTo(type = "REGION", elementClass = Zone.class, direction = Direction.OUTGOING)
    private Zone zone;

    @RelatedTo(type = "HOURLYDEMAND", elementClass = HourlyCSVTimeSeries.class, direction = Direction.OUTGOING)
    private HourlyCSVTimeSeries hourlyDemand;

    private double capacityMultiplicationFactor;

    private double compensationGovernment;

    private double compensationLocals;

    private double minPopulationDensity;
    private double deltaPopulationDensity;
    private double maxPopulationDensity;

    private double minWealth;
    private double maxWealth;
    private double deltaWealth;

    private double maxTechnologyPreference;
    private double deltaTechnologyPreference;

    private double maxDistanceGrid;
    private double deltaDistanceGrid;

    private double minQualityWater;
    private double deltaQualityWater;

    private double minWindPower;
    private double deltaWindPower;

    private double maxWaterDepth;
    private double deltaWaterDepth;

    private double maxDistanceShore;
    private double deltaDistanceShore;

    private double minSunHours;
    private double deltaSunHours;

    private double minEnvironmentalCosts;
    private double deltaEnvironmentalCosts;

    private double minPlantsOfTechnology;
    private double deltaPlantsOfTechnology;
    private double deltaPlantsOfTechnologyWind;

    private double maxCompensationGovernmentPercentageOfInvestment;

    private double minEmployment;
    private double deltaEmployment;

    private double timeNotUsingTech;
    private double timeNotUsingLocation;

    public double getTimeNotUsingTech() {
        return timeNotUsingTech;
    }

    public void setTimeNotUsingTech(double timeNotUsingTech) {
        this.timeNotUsingTech = timeNotUsingTech;
    }

    public double getTimeNotUsingLocation() {
        return timeNotUsingLocation;
    }

    public void setTimeNotUsingLocation(double timeNotUsingLocation) {
        this.timeNotUsingLocation = timeNotUsingLocation;
    }

    public double getMinPopulationDensity() {
        return minPopulationDensity;
    }

    public void setMinPopulationDensity(double minPopulationDensity) {
        this.minPopulationDensity = minPopulationDensity;
    }

    public double getDeltaPopulationDensity() {
        return deltaPopulationDensity;
    }

    public void setDeltaPopulationDensity(double deltaPopulationDensity) {
        this.deltaPopulationDensity = deltaPopulationDensity;
    }

    public double getMaxPopulationDensity() {
        return maxPopulationDensity;
    }

    public void setMaxPopulationDensity(double maxPopulationDensity) {
        this.maxPopulationDensity = maxPopulationDensity;
    }

    public double getMinWealth() {
        return minWealth;
    }

    public void setMinWealth(double minWealth) {
        this.minWealth = minWealth;
    }

    public double getMaxWealth() {
        return maxWealth;
    }

    public void setMaxWealth(double maxWealth) {
        this.maxWealth = maxWealth;
    }

    public double getDeltaWealth() {
        return deltaWealth;
    }

    public void setDeltaWealth(double deltaWealth) {
        this.deltaWealth = deltaWealth;
    }

    public double getMaxTechnologyPreference() {
        return maxTechnologyPreference;
    }

    public void setMaxTechnologyPreference(double maxTechnologyPreference) {
        this.maxTechnologyPreference = maxTechnologyPreference;
    }

    public double getDeltaTechnologyPreference() {
        return deltaTechnologyPreference;
    }

    public void setDeltaTechnologyPreference(double deltaTechnologyPreference) {
        this.deltaTechnologyPreference = deltaTechnologyPreference;
    }

    public double getMaxDistanceGrid() {
        return maxDistanceGrid;
    }

    public void setMaxDistanceGrid(double maxDistanceGrid) {
        this.maxDistanceGrid = maxDistanceGrid;
    }

    public double getDeltaDistanceGrid() {
        return deltaDistanceGrid;
    }

    public void setDeltaDistanceGrid(double deltaDistanceGrid) {
        this.deltaDistanceGrid = deltaDistanceGrid;
    }

    public double getMinQualityWater() {
        return minQualityWater;
    }

    public void setMinQualityWater(double minQualityWater) {
        this.minQualityWater = minQualityWater;
    }

    public double getDeltaQualityWater() {
        return deltaQualityWater;
    }

    public void setDeltaQualityWater(double deltaQualityWater) {
        this.deltaQualityWater = deltaQualityWater;
    }

    public double getMinWindPower() {
        return minWindPower;
    }

    public void setMinWindPower(double minWindPower) {
        this.minWindPower = minWindPower;
    }

    public double getDeltaWindPower() {
        return deltaWindPower;
    }

    public void setDeltaWindPower(double deltaWindPower) {
        this.deltaWindPower = deltaWindPower;
    }

    public double getMaxWaterDepth() {
        return maxWaterDepth;
    }

    public void setMaxWaterDepth(double maxWaterDepth) {
        this.maxWaterDepth = maxWaterDepth;
    }

    public double getDeltaWaterDepth() {
        return deltaWaterDepth;
    }

    public void setDeltaWaterDepth(double deltaWaterDepth) {
        this.deltaWaterDepth = deltaWaterDepth;
    }

    public double getMaxDistanceShore() {
        return maxDistanceShore;
    }

    public void setMaxDistanceShore(double maxDistanceShore) {
        this.maxDistanceShore = maxDistanceShore;
    }

    public double getDeltaDistanceShore() {
        return deltaDistanceShore;
    }

    public void setDeltaDistanceShore(double deltaDistanceShore) {
        this.deltaDistanceShore = deltaDistanceShore;
    }

    public double getMinSunHours() {
        return minSunHours;
    }

    public void setMinSunHours(double minSunHours) {
        this.minSunHours = minSunHours;
    }

    public double getDeltaSunHours() {
        return deltaSunHours;
    }

    public void setDeltaSunHours(double deltaSunHours) {
        this.deltaSunHours = deltaSunHours;
    }

    public double getMinEnvironmentalCosts() {
        return minEnvironmentalCosts;
    }

    public void setMinEnvironmentalCosts(double minEnvironmentalCosts) {
        this.minEnvironmentalCosts = minEnvironmentalCosts;
    }

    public double getDeltaEnvironmentalCosts() {
        return deltaEnvironmentalCosts;
    }

    public void setDeltaEnvironmentalCosts(double deltaEnvironmentalCosts) {
        this.deltaEnvironmentalCosts = deltaEnvironmentalCosts;
    }

    public double getMinPlantsOfTechnology() {
        return minPlantsOfTechnology;
    }

    public void setMinPlantsOfTechnology(double minPlantsOfTechnology) {
        this.minPlantsOfTechnology = minPlantsOfTechnology;
    }

    public double getDeltaPlantsOfTechnology() {
        return deltaPlantsOfTechnology;
    }

    public void setDeltaPlantsOfTechnology(double deltaPlantsOfTechnology) {
        this.deltaPlantsOfTechnology = deltaPlantsOfTechnology;
    }

    public double getDeltaPlantsOfTechnologyWind() {
        return deltaPlantsOfTechnologyWind;
    }

    public void setDeltaPlantsOfTechnologyWind(double deltaPlantsOfTechnologyWind) {
        this.deltaPlantsOfTechnologyWind = deltaPlantsOfTechnologyWind;
    }

    public double getMaxCompensationGovernmentPercentageOfInvestment() {
        return maxCompensationGovernmentPercentageOfInvestment;
    }

    public void setMaxCompensationGovernmentPercentageOfInvestment(
            double maxCompensationGovernmentPercentageOfInvestment) {
        this.maxCompensationGovernmentPercentageOfInvestment = maxCompensationGovernmentPercentageOfInvestment;
    }

    public double getMinEmployment() {
        return minEmployment;
    }

    public void setMinEmployment(double minEmployment) {
        this.minEmployment = minEmployment;
    }

    public double getDeltaEmployment() {
        return deltaEmployment;
    }

    public void setDeltaEmployment(double deltaEmployment) {
        this.deltaEmployment = deltaEmployment;
    }

    public double getCompensationGovernment() {
        return compensationGovernment;
    }

    public void setCompensationGovernment(double compensationGovernment) {
        this.compensationGovernment = compensationGovernment;
    }

    public double getCompensationLocals() {
        return compensationLocals;
    }

    public void setCompensationLocals(double compensationLocals) {
        this.compensationLocals = compensationLocals;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private double maximumCcsInNode;

    public double getMaximumCcsInNode() {
        return maximumCcsInNode;
    }

    public void setMaximumCcsInNode(double maximumCcsInNode) {
        this.maximumCcsInNode = maximumCcsInNode;
    }

    public HourlyCSVTimeSeries getHourlyDemand() {
        return hourlyDemand;
    }

    public void setHourlyDemand(HourlyCSVTimeSeries hourlydemand) {
        this.hourlyDemand = hourlydemand;
    }

    public void setZone(Zone zone) {
        this.zone = zone;
    }

    public Zone getZone() {
        return zone;
    }

    @Value("1.0")
    public double getCapacityMultiplicationFactor() {
        return capacityMultiplicationFactor;
    }

    public void setCapacityMultiplicationFactor(double capacityMultiplicationFactor) {
        this.capacityMultiplicationFactor = capacityMultiplicationFactor;
    }

}
