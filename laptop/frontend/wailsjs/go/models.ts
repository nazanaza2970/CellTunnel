export namespace main {
	
	export class Status {
	    running: boolean;
	    uptime: string;
	    device: string;
	    log: string;
	
	    static createFrom(source: any = {}) {
	        return new Status(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.running = source["running"];
	        this.uptime = source["uptime"];
	        this.device = source["device"];
	        this.log = source["log"];
	    }
	}

}

