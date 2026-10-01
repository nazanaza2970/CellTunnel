package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

func main() {
	proxy := flag.String("proxy", "192.168.4.2:12345", "phone address as seen over WiFi, host:port")
	pass := flag.String("pass", "", "password set in the phone app (empty for open access)")
	dev := flag.String("device", "celltun", "TUN device name")
	mtu := flag.Int("mtu", 1400, "TUN MTU")
	flag.Parse()

	url := "socks5://"
	if *pass != "" {
		url += "user:" + *pass + "@"
	}
	url += *proxy

	key := &engine.Key{
		Device: "tun://" + *dev,
		Proxy:  url,
		MTU:    *mtu,
	}
	engine.Insert(key)
	engine.Start()
	defer engine.Stop()

	log.Printf("TUN %s is up, tunneling to %s", *dev, *proxy)
	fmt.Println("Now run (Linux, as root):")
	fmt.Printf("  ip addr add 198.18.0.1/32 dev %s\n", *dev)
	fmt.Printf("  ip route add default dev %s\n", *dev)
	fmt.Println("  sh -c 'echo nameserver 1.1.1.1 > /etc/resolv.conf'")
	fmt.Println("(Windows: netsh interface ipv4 set address \"celltun\" static 198.18.0.1 255.255.255.255 && netsh interface ipv4 add route 0.0.0.0 0.0.0.0 celltun && netsh interface ipv4 set dns celltun static 1.1.1.1)")

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
}
